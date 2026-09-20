<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api, ApiClientError } from '@/api/client'
import type { ModelRuntimeView, UserDirectoryView, WorkspaceMemberRole, WorkspaceView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import { authStore } from '@/stores/auth'
import { toastStore } from '@/stores/toast'

type Accent = 'sage' | 'blue' | 'violet'
interface Preferences {
  workspaceName: string
  language: string
  density: 'comfortable' | 'compact'
  accent: Accent
  notifyFailures: boolean
  retainDays: number
}

const STORAGE_KEY = 'personal_platform_preferences'
const defaults: Preferences = {
  workspaceName: 'Personal Platform', language: 'zh-CN', density: 'comfortable', accent: 'sage', notifyFailures: true, retainDays: 90,
}
const preferences = reactive<Preferences>({ ...defaults })
const accentOptions: Array<{ id: Accent; label: string }> = [
  { id: 'sage', label: 'Sage' }, { id: 'blue', label: 'Blue' }, { id: 'violet', label: 'Violet' },
]
const runtime = ref<ModelRuntimeView | null>(null)
const runtimeError = ref('')
const runtimeLoading = ref(true)
const workspaces = ref<WorkspaceView[]>([])
const directory = ref<UserDirectoryView[]>([])
const selectedWorkspace = ref<WorkspaceView | null>(null)
const newWorkspaceName = ref('')
const newWorkspaceColor = ref('#477467')
const memberUserId = ref<number | null>(null)
const memberRole = ref<WorkspaceMemberRole>('MEMBER')

onMounted(async () => {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored) Object.assign(preferences, JSON.parse(stored))
  } catch { localStorage.removeItem(STORAGE_KEY) }
  applyAppearance()
  await Promise.all([loadRuntime(), loadWorkspaces()])
})

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

async function loadRuntime() {
  runtimeLoading.value = true
  runtimeError.value = ''
  try { runtime.value = await api.modelRuntime() }
  catch (reason) { runtimeError.value = reason instanceof ApiClientError ? reason.message : '模型运行时信息加载失败' }
  finally { runtimeLoading.value = false }
}

function applyAppearance() {
  const palette: Record<Accent, [string, string]> = {
    sage: ['#477467', '#e7f0ec'], blue: ['#3f67a5', '#e9eef8'], violet: ['#6957a8', '#eeeafb'],
  }
  document.documentElement.style.setProperty('--green', palette[preferences.accent][0])
  document.documentElement.style.setProperty('--green-soft', palette[preferences.accent][1])
  document.body.dataset.density = preferences.density
}

function save() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(preferences))
  applyAppearance()
  toastStore.success('设置已保存在当前浏览器')
}

function reset() {
  Object.assign(preferences, defaults)
  localStorage.removeItem(STORAGE_KEY)
  applyAppearance()
  toastStore.info('已恢复默认设置')
}
</script>

<template>
  <div class="page-stack settings-page">
    <section class="page-intro"><p class="page-kicker">WORKSPACE</p><h2>Settings</h2><p>管理界面偏好、模型运行时状态和当前账号信息。</p></section>

    <section class="settings-grid">
      <div class="settings-main">
        <article class="panel settings-card">
          <header><div><h3>通用</h3><p>这些偏好保存在当前浏览器中。</p></div><AppIcon name="settings" :size="21" /></header>
          <div class="settings-fields">
            <label class="field-label">工作空间名称<input v-model="preferences.workspaceName" class="field-input" maxlength="60" /></label>
            <label class="field-label">界面语言<select v-model="preferences.language" class="field-input"><option value="zh-CN">简体中文</option><option value="en-US">English</option></select></label>
            <label class="field-label">内容密度<select v-model="preferences.density" class="field-input"><option value="comfortable">舒适</option><option value="compact">紧凑</option></select></label>
            <label class="field-label">日志保留天数<input v-model.number="preferences.retainDays" class="field-input" type="number" min="7" max="365" /></label>
          </div>
          <label class="settings-toggle"><span><strong>失败任务提醒</strong><small>在控制台突出显示失败和密钥错误。</small></span><input v-model="preferences.notifyFailures" type="checkbox" /><i /></label>
        </article>

        <article v-if="authStore.has('workspace:manage')" class="panel settings-card workspace-settings">
          <header><div><h3>工作空间与成员</h3><p>像 GitHub 组织一样创建空间、设置颜色并控制成员角色。</p></div><AppIcon name="users" :size="21" /></header>
          <form class="workspace-create" @submit.prevent="createWorkspace"><input v-model="newWorkspaceName" class="field-input" maxlength="100" placeholder="新工作空间名称" required /><input v-model="newWorkspaceColor" type="color" aria-label="工作空间颜色" /><button class="button button--dark">新建</button></form>
          <div class="workspace-tabs"><button v-for="item in workspaces" :key="item.id" :class="{ active: selectedWorkspace?.id === item.id }" @click="selectedWorkspace = item"><i :style="{ background: item.color }" />{{ item.name }}<small>{{ item.members.length }}</small></button></div>
          <div v-if="selectedWorkspace" class="workspace-editor"><div class="settings-fields"><label class="field-label">名称<input v-model="selectedWorkspace.name" class="field-input" /></label><label class="field-label">颜色<input v-model="selectedWorkspace.color" class="field-input color-input" type="color" /></label></div><button class="button button--ghost" @click="saveWorkspace">保存空间设置</button><div class="workspace-members"><h4>成员</h4><div v-for="member in selectedWorkspace.members" :key="member.id"><span>{{ member.displayName.slice(0, 1) }}</span><p><strong>{{ member.displayName }}</strong><small>@{{ member.username }}</small></p><b>{{ member.role }}</b><button v-if="member.role !== 'OWNER' && ['OWNER', 'ADMIN'].includes(selectedWorkspace.currentRole)" @click="removeMember(member.userId)"><AppIcon name="close" :size="15" /></button></div></div><form v-if="['OWNER', 'ADMIN'].includes(selectedWorkspace.currentRole)" class="workspace-add-member" @submit.prevent="addMember"><select v-model="memberUserId" class="field-input" required><option :value="null" disabled>选择用户</option><option v-for="user in directory" :key="user.id" :value="user.id">{{ user.displayName }} · @{{ user.username }}</option></select><select v-model="memberRole" class="field-input"><option value="ADMIN">管理员</option><option value="MEMBER">成员</option><option value="VIEWER">查看者</option></select><button class="button button--dark">添加 / 更新</button></form></div>
          <div v-else class="inline-alert">选择一个工作空间，或新建第一个空间。</div>
        </article>

        <article class="panel settings-card">
          <header><div><h3>外观</h3><p>选择强调色；页面布局和对比度保持一致。</p></div><AppIcon name="eye" :size="21" /></header>
          <div class="accent-options"><button v-for="item in accentOptions" :key="item.id" :class="[`accent-${item.id}`, { active: preferences.accent === item.id }]" @click="preferences.accent = item.id"><i /><span>{{ item.label }}</span><AppIcon v-if="preferences.accent === item.id" name="check" :size="16" /></button></div>
        </article>

        <div class="settings-actions"><button class="button button--ghost" @click="reset">恢复默认</button><button class="button button--dark" @click="save">保存设置</button></div>
      </div>

      <aside class="settings-side">
        <article class="panel runtime-card">
          <header><span><i :class="{ ready: runtime?.credentialConfigured }" /></span><button class="icon-button" title="刷新" @click="loadRuntime"><AppIcon name="logs" :size="17" /></button></header>
          <p class="page-kicker">MODEL RUNTIME</p><h3>{{ runtime?.model || (runtimeLoading ? '正在读取…' : '未连接') }}</h3>
          <p v-if="runtimeError" class="runtime-error">{{ runtimeError }}</p>
          <dl v-else-if="runtime"><dt>Provider</dt><dd>{{ runtime.provider }}</dd><dt>Mode</dt><dd><code>{{ runtime.mode }}</code></dd><dt>Credential</dt><dd>{{ runtime.credentialConfigured ? 'Configured' : 'Missing' }}</dd><dt>Vision</dt><dd>{{ runtime.capabilities.vision ? 'Available' : 'Unavailable' }}</dd></dl>
          <div class="runtime-note"><AppIcon name="key" :size="18" /><span>API 密钥只由 Java 后端读取，前端不会返回或显示密钥内容。</span></div>
        </article>
        <article class="panel account-card"><div class="avatar">{{ authStore.state.user?.displayName?.slice(0, 1) }}</div><div><strong>{{ authStore.state.user?.displayName }}</strong><span>{{ authStore.state.user?.email }}</span><small>{{ authStore.state.user?.roleName }}</small></div></article>
      </aside>
    </section>
  </div>
</template>
