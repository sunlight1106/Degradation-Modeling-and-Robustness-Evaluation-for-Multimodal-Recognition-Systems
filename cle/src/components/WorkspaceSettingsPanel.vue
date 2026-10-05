<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, ApiClientError } from '@/api/client'
import type { UserDirectoryView, WorkspaceMemberRole, WorkspaceView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import { authStore } from '@/stores/auth'
import { toastStore } from '@/stores/toast'
const workspaces = ref<WorkspaceView[]>([])
const directory = ref<UserDirectoryView[]>([])
const selectedWorkspace = ref<WorkspaceView | null>(null)
const newWorkspaceName = ref('')
const newWorkspaceColor = ref('#477467')
const memberUserId = ref<number | null>(null)
const memberRole = ref<WorkspaceMemberRole>('MEMBER')

async function loadWorkspaces() {
  if (!authStore.has('workspace:manage')) return
  try {
    const [items, users] = await Promise.all([api.workspaces(), authStore.has('message:read') ? api.messageDirectory() : Promise.resolve([])])
    workspaces.value = items; directory.value = users
    if (selectedWorkspace.value) selectedWorkspace.value = items.find(item => item.id === selectedWorkspace.value?.id) || null
  } catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '工作空间加载失败') }
}

async function createWorkspace() {
  if (!newWorkspaceName.value.trim()) return
  try { selectedWorkspace.value = await api.createWorkspace({ name: newWorkspaceName.value, color: newWorkspaceColor.value }); newWorkspaceName.value = ''; await loadWorkspaces(); toastStore.success('工作空间已创建') }
  catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '创建失败') }
}

async function saveWorkspace() {
  if (!selectedWorkspace.value) return
  try { selectedWorkspace.value = await api.updateWorkspace(selectedWorkspace.value.id, { name: selectedWorkspace.value.name, color: selectedWorkspace.value.color }); await loadWorkspaces(); toastStore.success('工作空间设置已保存') }
  catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '保存失败') }
}

async function addMember() {
  if (!selectedWorkspace.value || !memberUserId.value) return
  try { selectedWorkspace.value = await api.upsertWorkspaceMember(selectedWorkspace.value.id, { userId: memberUserId.value, role: memberRole.value, permissions: memberRole.value === 'VIEWER' ? ['CONTENT_READ'] : ['CONTENT_READ', 'CONTENT_WRITE', 'MEMBERS_READ'] }); memberUserId.value = null; await loadWorkspaces(); toastStore.success('成员权限已更新') }
  catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '成员更新失败') }
}

async function removeMember(userId: number) {
  if (!selectedWorkspace.value) return
  try { selectedWorkspace.value = await api.removeWorkspaceMember(selectedWorkspace.value.id, userId); await loadWorkspaces(); toastStore.info('成员已移除') }
  catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '移除失败') }
}


onMounted(loadWorkspaces)
</script>
<template>
        <article v-if="authStore.has('workspace:manage')" class="panel settings-card workspace-settings">
          <header><div><h3>工作空间与成员</h3><p>像 GitHub 组织一样创建空间、设置颜色并控制成员角色。</p></div><AppIcon name="users" :size="21" /></header>
          <form class="workspace-create" @submit.prevent="createWorkspace"><input v-model="newWorkspaceName" class="field-input" maxlength="100" placeholder="新工作空间名称" required /><input v-model="newWorkspaceColor" type="color" aria-label="工作空间颜色" /><button class="button button--dark">新建</button></form>
          <div class="workspace-tabs"><button v-for="item in workspaces" :key="item.id" :class="{ active: selectedWorkspace?.id === item.id }" @click="selectedWorkspace = item"><i :style="{ background: item.color }" />{{ item.name }}<small>{{ item.members.length }}</small></button></div>
          <div v-if="selectedWorkspace" class="workspace-editor"><div class="settings-fields"><label class="field-label">名称<input v-model="selectedWorkspace.name" class="field-input" /></label><label class="field-label">颜色<input v-model="selectedWorkspace.color" class="field-input color-input" type="color" /></label></div><button class="button button--ghost" @click="saveWorkspace">保存空间设置</button><div class="workspace-members"><h4>成员</h4><div v-for="member in selectedWorkspace.members" :key="member.id"><span>{{ member.displayName.slice(0, 1) }}</span><p><strong>{{ member.displayName }}</strong><small>@{{ member.username }}</small></p><b>{{ member.role }}</b><button v-if="member.role !== 'OWNER' && ['OWNER', 'ADMIN'].includes(selectedWorkspace.currentRole)" @click="removeMember(member.userId)"><AppIcon name="close" :size="15" /></button></div></div><form v-if="['OWNER', 'ADMIN'].includes(selectedWorkspace.currentRole)" class="workspace-add-member" @submit.prevent="addMember"><select v-model="memberUserId" class="field-input" required><option :value="null" disabled>选择用户</option><option v-for="user in directory" :key="user.id" :value="user.id">{{ user.displayName }} · @{{ user.username }}</option></select><select v-model="memberRole" class="field-input"><option value="ADMIN">管理员</option><option value="MEMBER">成员</option><option value="VIEWER">查看者</option></select><button class="button button--dark">添加 / 更新</button></form></div>
          <div v-else class="inline-alert">选择一个工作空间，或新建第一个空间。</div>
        </article>
</template>
