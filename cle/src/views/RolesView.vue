<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, ApiClientError } from '@/api/client'
import type { PermissionView, RoleView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'

const roles = ref<RoleView[]>([])
const permissions = ref<PermissionView[]>([])
const selectedId = ref<number | null>(null)
const selectedPermissions = ref<string[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const creating = ref(false)
const newCode = ref('')
const newName = ref('')
const newDescription = ref('')

const selectedRole = computed(() => roles.value.find(role => role.id === selectedId.value) || null)
const canWrite = computed(() => authStore.has('role:write'))
const groups = computed(() => {
  const result = new Map<string, PermissionView[]>()
  permissions.value.forEach(permission => result.set(permission.group, [...(result.get(permission.group) || []), permission]))
  return result
})

onMounted(async () => {
  try {
    const [roleList, permissionList] = await Promise.all([api.roles(), api.permissions()])
    roles.value = roleList
    permissions.value = permissionList
    selectedId.value = roles.value.find(role => role.code === 'RESEARCHER')?.id || roles.value[0]?.id || null
  } catch (reason) { error.value = reason instanceof ApiClientError ? reason.message : '权限数据加载失败' }
  finally { loading.value = false }
})

watch(selectedRole, role => { selectedPermissions.value = role ? [...role.permissions] : [] }, { immediate: true })

async function save() {
  if (!selectedRole.value || selectedRole.value.code === 'ADMIN') return
  saving.value = true
  try {
    const updated = await api.updateRolePermissions(selectedRole.value.id, selectedPermissions.value)
    const index = roles.value.findIndex(role => role.id === updated.id)
    if (index >= 0) roles.value[index] = updated
    toastStore.success('角色权限已保存')
  } catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '权限保存失败') }
  finally { saving.value = false }
}

async function createRole() {
  if (!newCode.value || !newName.value) return
  saving.value = true
  try {
    const created = await api.createRole({ code: newCode.value.toUpperCase(), name: newName.value, description: newDescription.value, permissions: selectedPermissions.value })
    roles.value.push(created); selectedId.value = created.id; creating.value = false; newCode.value = ''; newName.value = ''; newDescription.value = ''
    toastStore.success('新角色已创建')
  } catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '角色创建失败') }
  finally { saving.value = false }
}
</script>

<template>
  <div class="page-stack">
    <section class="page-intro page-intro--split"><div><p class="page-kicker">RBAC</p><h2>权限管理</h2><p>管理员拥有最高权限，并可新建角色、组合权限，再分配给平台用户。</p></div><button v-if="canWrite" class="button button--dark" @click="creating = true"><AppIcon name="plus" :size="16" /> 新建角色</button></section>
    <div v-if="loading" class="large-skeleton" />
    <div v-else-if="error" class="inline-alert inline-alert--error">{{ error }}</div>
    <section v-else class="role-layout">
      <aside class="panel role-list"><div class="panel-header"><div><h3>角色</h3><p>{{ roles.length }} 个平台角色</p></div></div><button v-for="role in roles" :key="role.id" :class="{ active: selectedId === role.id }" @click="selectedId = role.id"><span class="role-icon"><AppIcon :name="role.code === 'ADMIN' ? 'shield' : role.code === 'RESEARCHER' ? 'spark' : 'users'" :size="19" /></span><span><strong>{{ role.name }}</strong><small>{{ role.description }}</small></span><b>{{ role.permissions.length }}</b></button></aside>
      <div v-if="selectedRole" class="panel permission-editor">
        <div class="panel-header"><div><p class="page-kicker">{{ selectedRole.code }}</p><h3>{{ selectedRole.name }}的权限</h3><p>{{ selectedRole.description }}</p></div><span v-if="selectedRole.code === 'ADMIN' || !canWrite" class="locked-label"><AppIcon name="shield" :size="15" /> {{ selectedRole.code === 'ADMIN' ? '固定权限' : '只读' }}</span></div>
        <div v-if="selectedRole.code === 'ADMIN'" class="inline-alert">为避免平台失去管理入口，管理员角色固定拥有全部权限。</div>
        <div class="permission-groups">
          <fieldset v-for="[group, items] in groups" :key="group"><legend>{{ group }}</legend><label v-for="permission in items" :key="permission.code" class="permission-row"><input v-model="selectedPermissions" type="checkbox" :value="permission.code" :disabled="selectedRole.code === 'ADMIN' || !canWrite" /><span class="custom-check"><AppIcon name="check" :size="14" /></span><span><strong>{{ permission.label }}</strong><code>{{ permission.code }}</code></span></label></fieldset>
        </div>
        <div class="permission-footer"><span>已选择 {{ selectedPermissions.length }} / {{ permissions.length }} 项权限</span><button v-if="canWrite" class="button button--dark" :disabled="selectedRole.code === 'ADMIN' || saving" @click="save">{{ saving ? '正在保存…' : '保存权限' }}</button></div>
      </div>
    </section>
    <div v-if="creating" class="modal-backdrop" @click.self="creating = false"><form class="modal-card" @submit.prevent="createRole"><header><div><p class="page-kicker">CUSTOM ROLE</p><h3>新建权限角色</h3></div><button type="button" class="icon-button" @click="creating = false"><AppIcon name="close" /></button></header><label class="field-label">角色编码<input v-model="newCode" class="field-input" pattern="[A-Za-z][A-Za-z0-9_]{2,39}" placeholder="DATA_AUDITOR" required /></label><label class="field-label">显示名称<input v-model="newName" class="field-input" maxlength="80" placeholder="数据审计员" required /></label><label class="field-label">说明<textarea v-model="newDescription" class="field-input" rows="3" maxlength="255" /></label><p>将复制当前编辑区选中的 {{ selectedPermissions.length }} 项权限，创建后仍可调整。</p><button class="button button--dark button--full" :disabled="saving">{{ saving ? '正在创建…' : '创建角色' }}</button></form></div>
  </div>
</template>
