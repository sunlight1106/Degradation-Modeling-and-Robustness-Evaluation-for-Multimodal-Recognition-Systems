<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api, ApiClientError } from '@/api/client'
import type { RoleView, UserView } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import { authStore } from '@/stores/auth'
import { toastStore } from '@/stores/toast'

const users = ref<UserView[]>([])
const roles = ref<RoleView[]>([])
const loading = ref(true)
const showCreate = ref(false)
const saving = ref(false)
const error = ref('')
const form = reactive({ username: '', password: '', displayName: '', email: '', roleId: 0 })

const activeCount = computed(() => users.value.filter(user => user.status === 'ACTIVE').length)
const canWrite = computed(() => authStore.has('user:write'))
const canReadRoles = computed(() => authStore.has('role:read'))

onMounted(load)
async function load() {
  try {
    const [userList, roleList] = await Promise.all([
      api.users(),
      canReadRoles.value ? api.roles() : Promise.resolve([] as RoleView[]),
    ])
    users.value = userList
    roles.value = roleList
    if (!form.roleId) form.roleId = roles.value.find(role => role.code === 'RESEARCHER')?.id || roles.value[0]?.id || 0
  } catch (reason) { error.value = reason instanceof ApiClientError ? reason.message : '用户数据加载失败' }
  finally { loading.value = false }
}

async function createUser() {
  saving.value = true
  error.value = ''
  try {
    const created = await api.createUser({ ...form })
    users.value.unshift(created)
    Object.assign(form, { username: '', password: '', displayName: '', email: '', roleId: form.roleId })
    showCreate.value = false
    toastStore.success('用户已创建')
  } catch (reason) { error.value = reason instanceof ApiClientError ? reason.message : '创建用户失败' }
  finally { saving.value = false }
}

async function toggleStatus(user: UserView) {
  try {
    const updated = await api.updateUser(user.id, { status: user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' })
    replace(updated)
    toastStore.success(updated.status === 'ACTIVE' ? '用户已启用' : '用户已停用')
  } catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '更新失败') }
}

async function changeRole(user: UserView, event: Event) {
  const roleId = Number((event.target as HTMLSelectElement).value)
  try { replace(await api.updateUser(user.id, { roleId })); toastStore.success('角色已更新') }
  catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '角色更新失败'); await load() }
}

function replace(updated: UserView) {
  const index = users.value.findIndex(user => user.id === updated.id)
  if (index >= 0) users.value[index] = updated
}

const formatDate = (value: string) => new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value))
</script>

<template>
  <div class="page-stack">
    <section class="page-intro page-intro--split"><div><p class="page-kicker">账号与访问</p><h2>用户管理</h2><p>创建平台账号、分配角色并控制启用状态。</p></div><button v-if="canWrite && roles.length" class="button button--dark" @click="showCreate = !showCreate"><AppIcon :name="showCreate ? 'close' : 'users'" :size="17" /> {{ showCreate ? '取消创建' : '新建用户' }}</button></section>

    <form v-if="showCreate" class="panel create-user-form" @submit.prevent="createUser">
      <div class="panel-header"><div><h3>创建用户</h3><p>密码至少 8 位；用户登录后应自行修改</p></div></div>
      <div class="form-grid"><label class="field-label">用户名<input v-model="form.username" class="field-input" required pattern="[A-Za-z0-9._-]{3,60}" placeholder="例如 researcher01" /></label><label class="field-label">显示名称<input v-model="form.displayName" class="field-input" required maxlength="80" placeholder="例如 数据研究员" /></label><label class="field-label">邮箱<input v-model="form.email" class="field-input" type="email" required placeholder="name@example.com" /></label><label class="field-label">初始密码<input v-model="form.password" class="field-input" type="password" required minlength="8" maxlength="72" placeholder="至少 8 位" /></label><label class="field-label">角色<select v-model.number="form.roleId" class="field-input" required><option v-for="role in roles" :key="role.id" :value="role.id">{{ role.name }}</option></select></label></div>
      <div v-if="error" class="inline-alert inline-alert--error">{{ error }}</div>
      <div class="form-actions"><button type="button" class="button button--ghost" @click="showCreate = false">取消</button><button class="button button--dark" :disabled="saving">{{ saving ? '正在创建…' : '创建用户' }}</button></div>
    </form>

    <section class="user-stat-row"><div><strong>{{ users.length }}</strong><span>全部用户</span></div><div><strong>{{ activeCount }}</strong><span>启用账号</span></div><div><strong>{{ users.length - activeCount }}</strong><span>停用账号</span></div><div><strong>{{ roles.length }}</strong><span>角色类型</span></div></section>

    <section class="panel">
      <div class="panel-header"><div><h3>平台用户</h3><p>权限以角色为单位统一管理</p></div></div>
      <div v-if="loading" class="table-skeleton" />
      <EmptyState v-else-if="!users.length" title="暂无用户" icon="users" />
      <div v-else class="data-table-wrap"><table class="data-table"><thead><tr><th>用户</th><th>角色</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead><tbody>
        <tr v-for="user in users" :key="user.id"><td><div class="table-primary"><span class="avatar avatar--small">{{ user.displayName.slice(0, 1) }}</span><span><strong>{{ user.displayName }} <i v-if="user.id === authStore.state.user?.id" class="self-label">当前账号</i></strong><small>@{{ user.username }} · {{ user.email }}</small></span></div></td><td><select v-if="canWrite && roles.length" class="compact-select" :value="user.roleId" :disabled="user.id === authStore.state.user?.id" @change="changeRole(user, $event)"><option v-for="role in roles" :key="role.id" :value="role.id">{{ role.name }}</option></select><span v-else>{{ user.roleName }}</span></td><td><StatusBadge :status="user.status" /></td><td>{{ formatDate(user.createdAt) }}</td><td><button v-if="canWrite" class="table-action" :disabled="user.id === authStore.state.user?.id" @click="toggleStatus(user)">{{ user.status === 'ACTIVE' ? '停用' : '启用' }}</button><span v-else class="table-muted">只读</span></td></tr>
      </tbody></table></div>
    </section>
  </div>
</template>
