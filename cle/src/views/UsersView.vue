<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { api, ApiClientError } from '@/api/client'
import { adminApi, type Page } from '@/api/admin'
import type { PermissionView, RoleView, UserView } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import IdentityCode from '@/components/IdentityCode.vue'
import AdminUserDetail from '@/components/AdminUserDetail.vue'
import { authStore } from '@/stores/auth'
import { toastStore } from '@/stores/toast'
const users = ref<Page<UserView> | null>(null), roles = ref<RoleView[]>([]), permissions = ref<PermissionView[]>([])
const loading = ref(false), saving = ref(false), error = ref(''), showCreate = ref(false), selected = ref<number | null>(null)
const query = ref(''), status = ref(''), role = ref(''), page = ref(0)
const form = reactive({ username: '', password: '', displayName: '', email: '', roleId: 0 })
const admin = computed(() => authStore.state.user?.roleCode === 'ADMIN')
let generation = 0, active = true
async function load() {
  const version = ++generation; loading.value = true; error.value = ''
  try { const result = await adminApi.users(query.value, status.value, role.value, page.value); if (active && version === generation) users.value = result }
  catch (reason) { if (active && version === generation) error.value = reason instanceof ApiClientError ? reason.message : '用户加载失败' }
  finally { if (active && version === generation) loading.value = false }
}
function search() { page.value = 0; void load() }
function move(delta: number) { page.value += delta; void load() }
function replace(user: UserView) { const i = users.value?.items.findIndex(u => u.id === user.id) ?? -1; if (users.value && i >= 0) users.value.items[i] = user }
async function createUser() {
  if (saving.value) return; saving.value = true; error.value = ''
  try { await api.createUser(form); if (!active) return; showCreate.value = false; form.username = ''; form.password = ''; form.displayName = ''; form.email = ''; toastStore.success('用户已创建'); page.value = 0; await load() }
  catch (reason) { if (active) error.value = reason instanceof ApiClientError ? reason.message : '创建失败' }
  finally { form.password = ''; if (active) saving.value = false }
}
onMounted(async () => {
  await load(); if (!authStore.has('role:read')) return
  try { const [list, catalog] = await Promise.all([api.roles(), api.permissions()]); if (!active) return; roles.value = list; permissions.value = catalog; form.roleId = list.find(r => r.code === 'RESEARCHER')?.id || list[0]?.id || 0 }
  catch (reason) { if (active) error.value = reason instanceof ApiClientError ? reason.message : '角色加载失败' }
})
onBeforeUnmount(() => { active = false; generation++; form.password = '' })
const date = (value: string) => new Date(value).toLocaleDateString('zh-CN')
</script>
<template>
  <div class="page-stack user-management">
    <section class="page-intro page-intro--split"><div><p class="page-kicker">系统管理</p><h2>用户与使用权限</h2><p>按姓名、用户名、邮箱或身份码查找账号，在用户详情中调整权限。</p></div><button v-if="admin && roles.length" class="button button--dark" @click="showCreate = !showCreate">{{ showCreate ? '取消创建' : '新建用户' }}</button></section>
    <p v-if="error" class="inline-alert inline-alert--error" role="alert">{{ error }} <button class="table-action" @click="load">重试</button></p>
    <form v-if="showCreate" class="panel create-user-form" @submit.prevent="createUser"><h3>新建用户</h3><div class="form-grid"><label class="field-label">用户名<input v-model="form.username" class="field-input" required pattern="[A-Za-z0-9._-]{3,60}" autocomplete="off" /></label><label class="field-label">显示名称<input v-model="form.displayName" class="field-input" required maxlength="80" /></label><label class="field-label">邮箱<input v-model="form.email" class="field-input" type="email" required maxlength="160" /></label><label class="field-label">初始密码<input v-model="form.password" class="field-input" type="password" required minlength="8" maxlength="72" autocomplete="new-password" /></label><label class="field-label">角色<select v-model.number="form.roleId" class="field-input"><option v-for="r in roles" :key="r.id" :value="r.id">{{ r.name }}</option></select></label></div><p class="table-muted">身份码由系统分配。首次登录后可在设置中更改密码。</p><button class="button button--dark" :disabled="saving">{{ saving ? '正在创建…' : '创建账号' }}</button></form>
    <form class="user-search" @submit.prevent="search"><label class="user-search-query"><span class="sr-only">查找用户</span><input v-model="query" class="field-input" placeholder="搜索名称、用户名、邮箱或 Identity code" maxlength="120" /></label><label><span class="sr-only">账号状态</span><select v-model="status" class="field-input" @change="search"><option value="">全部状态</option><option value="ACTIVE">启用</option><option value="DISABLED">停用</option></select></label><label v-if="roles.length"><span class="sr-only">用户角色</span><select v-model="role" class="field-input" @change="search"><option value="">全部角色</option><option v-for="r in roles" :key="r.id" :value="String(r.id)">{{ r.name }}</option></select></label><button class="button button--dark" :disabled="loading">搜索</button></form>
    <section class="panel user-table" :aria-busy="loading"><header><h3>账号列表 <small>{{ users?.total ?? '—' }} 人</small></h3><span>每页 25 人</span></header><p v-if="loading && !users" role="status" class="user-empty">正在读取用户…</p><p v-else-if="users && !users.items.length" class="user-empty">没有符合条件的账号，试试其他名称或身份码。</p><div v-else-if="users" class="data-table-wrap"><table class="data-table"><thead><tr><th>用户</th><th>角色</th><th>状态</th><th>注册日期</th><th>管理</th></tr></thead><tbody><tr v-for="user in users.items" :key="user.id"><td><div class="table-primary"><span class="avatar avatar--small">{{ user.displayName.slice(0, 1) }}</span><span><strong>{{ user.displayName }} <i v-if="user.id === authStore.state.user?.id" class="self-label">当前账号</i></strong><small>@{{ user.username }} · {{ user.email }}</small><IdentityCode :value="user.identityCode" /></span></div></td><td>{{ user.roleName }}</td><td><span v-if="user.status === 'ACTIVE' && user.accessExpiresAt && new Date(user.accessExpiresAt).getTime() <= Date.now()" class="expired-user">已到期</span><StatusBadge v-else :status="user.status" /></td><td>{{ date(user.createdAt) }}</td><td><button class="table-action" @click="selected = user.id">查看详情{{ admin ? '与权限' : '' }} →</button></td></tr></tbody></table></div><footer v-if="users" class="user-pagination"><span>第 {{ page + 1 }} / {{ Math.max(1, Math.ceil(users.total / users.size)) }} 页</span><button class="button button--ghost" :disabled="page === 0 || loading" @click="move(-1)">上一页</button><button class="button button--ghost" :disabled="(page + 1) * users.size >= users.total || loading" @click="move(1)">下一页</button></footer></section>
    <AdminUserDetail v-if="selected !== null" :key="selected" :id="selected" :roles="roles" :permissions="permissions" @close="selected = null" @updated="replace" />
  </div>
</template>
<style scoped>
.expired-user{font-size:12px;color:var(--amber);background:var(--amber-soft);padding:4px 9px;border-radius:4px}.user-search{display:flex;gap:12px;align-items:center}.user-search-query{flex:1}.user-search>label:not(.user-search-query){max-width:190px}.user-search .field-input{height:44px}.user-table{padding:24px 28px}.user-table>header{display:flex;justify-content:space-between;align-items:center;margin-bottom:22px}.user-table h3{font-size:18px;margin:0}.user-table h3 small{font-size:13px;font-weight:400;color:var(--muted);margin-left:14px}.user-table>header>span{font-size:12px;color:var(--muted)}.user-pagination{display:flex;justify-content:flex-end;gap:15px;align-items:center;border-top:1px solid var(--line);padding-top:20px;font-size:13px;color:var(--muted)}.user-empty{padding:50px 0;color:var(--muted);text-align:center}.create-user-form{padding:24px 28px}.create-user-form h3{margin:0 0 22px}.create-user-form .table-muted{font-size:13px;margin:22px 0}.table-primary small{display:block}
</style>
