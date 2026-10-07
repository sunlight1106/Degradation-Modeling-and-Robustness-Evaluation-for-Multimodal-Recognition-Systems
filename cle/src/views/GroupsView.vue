<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import { authStore } from '@/stores/auth'
import type { MessageView, UserDirectoryView, WorkspaceMemberRole, WorkspaceView } from '@/types/api'

const groups = ref<WorkspaceView[]>([])
const selected = ref<WorkspaceView | null>(null)
const messages = ref<MessageView[]>([])
const directory = ref<UserDirectoryView[]>([])
const loading = ref(false), busy = ref(false), creating = ref(false)
const error = ref(''), notice = ref(''), groupName = ref(''), text = ref('')
const editName = ref('')
const files = ref<File[]>([]), fileInputKey = ref(0)
const reply = ref<MessageView | null>(null)
const memberId = ref<number | null>(null), memberRole = ref<WorkspaceMemberRole>('MEMBER')
const page = ref(0), more = ref(false)
let epoch = 0
const isAdmin = computed(() => authStore.state.user?.roleCode === 'ADMIN')
const canCreate = computed(() => isAdmin.value || authStore.has('workspace:manage'))
const canWrite = computed(() => selected.value?.currentPermissions.includes('CONTENT_WRITE') && selected.value?.currentPermissions.includes('CONTENT_READ'))
const canManage = computed(() => selected.value?.currentPermissions.includes('MEMBERS_WRITE'))
const canPromote = computed(() => isAdmin.value || selected.value?.ownerId === authStore.state.user?.id)
const canLeave = computed(() => selected.value?.ownerId !== authStore.state.user?.id && selected.value?.members.some(member => member.userId === authStore.state.user?.id))
const roles: Record<WorkspaceMemberRole, string> = { OWNER: '群主', ADMIN: '群管理员', MEMBER: '成员', VIEWER: '只读成员' }
const detail = (reason: unknown) => reason instanceof ApiClientError ? reason.message : '操作失败，请重试'
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })

function clearDraft() { text.value = ''; files.value = []; reply.value = null; fileInputKey.value++ }
function reset() { epoch++; groups.value = []; selected.value = null; messages.value = []; directory.value = []; clearDraft(); error.value = ''; notice.value = ''; busy.value = false; loading.value = false; memberId.value = null; groupName.value = ''; creating.value = false }
async function loadGroups() {
  const version = ++epoch
  loading.value = true; error.value = ''
  try {
    const result = await api.workspaces()
    if (version !== epoch) return
    groups.value = result
    if (result.length) await selectGroup(result[0].id)
  } catch (reason) { if (version === epoch) error.value = detail(reason) }
  finally { if (version === epoch) loading.value = false }
}
async function selectGroup(id: number) {
  const sameGroup = selected.value?.id === id
  const version = ++epoch
  selected.value = null; messages.value = []; directory.value = []; page.value = 0; more.value = false
  memberId.value = null; memberRole.value = 'MEMBER'; loading.value = true; error.value = ''; notice.value = ''
  if (!sameGroup) clearDraft()
  try {
    const group = await api.workspaces().then(items => { if (version === epoch) groups.value = items; return items.find(item => item.id === id) })
    if (version !== epoch) return
    if (!group) throw new Error('该群组已不可访问')
    selected.value = group
    editName.value = group.name
    if (group.currentPermissions.includes('CONTENT_READ')) {
      const result = await api.groupMessages(id)
      if (version !== epoch) return
      messages.value = result; more.value = result.length === 50
    }
    if (canManage.value) {
      const result = await api.workspaceDirectory(id)
      if (version === epoch) directory.value = result
    }
  } catch (reason) {
    if (version === epoch) { error.value = reason instanceof Error ? reason.message : detail(reason); selected.value = null; messages.value = []; directory.value = []; clearDraft() }
  } finally { if (version === epoch) loading.value = false }
}
async function older() {
  if (!selected.value || busy.value || loading.value) return
  const version = epoch, id = selected.value.id
  loading.value = true
  try {
    const result = await api.groupMessages(id, page.value + 1)
    if (version !== epoch) return
    const known = new Set(messages.value.map(item => item.id))
    messages.value.push(...result.filter(item => !known.has(item.id))); page.value++; more.value = result.length === 50
  } catch (reason) { if (version === epoch) { error.value = detail(reason); messages.value = [] } }
  finally { if (version === epoch) loading.value = false }
}
async function createGroup() {
  if (busy.value || !groupName.value.trim() || !canCreate.value) return
  const version = epoch
  busy.value = true; error.value = ''
  try {
    const group = await api.createWorkspace({ name: groupName.value.trim(), color: '#57788d' })
    if (version !== epoch) return
    groupName.value = ''; creating.value = false; busy.value = false
    await selectGroup(group.id)
  } catch (reason) { if (version === epoch) error.value = detail(reason) }
  finally { if (version === epoch) busy.value = false }
}
function chooseFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const chosen = Array.from(input.files || [])
  if (chosen.length > 5 || chosen.some(file => file.size > 20 * 1024 * 1024)) {
    error.value = '最多选择 5 个附件，每个不超过 20 MB。'; input.value = ''; files.value = []; return
  }
  files.value = chosen; error.value = ''
}
async function send() {
  if (!selected.value || !canWrite.value || busy.value || loading.value || (!text.value.trim() && !files.value.length)) return
  const version = epoch, id = selected.value.id
  busy.value = true; error.value = ''
  try {
    const message = await api.sendGroupMessage(id, { body: text.value.trim() || '分享资料', files: files.value, replyToId: reply.value?.id })
    if (version !== epoch) return
    messages.value.unshift(message); clearDraft(); notice.value = '已发送到当前群组'
  } catch (reason) { if (version === epoch) error.value = detail(reason) }
  finally { if (version === epoch) busy.value = false }
}
async function memberAction(userId: number, role?: WorkspaceMemberRole) {
  if (!selected.value || busy.value) return
  if (!role && !window.confirm('移除此成员？移除后将不能继续访问本群消息和资料。')) return
  const version = epoch, id = selected.value.id
  busy.value = true; error.value = ''
  try {
    const group = role ? await api.upsertWorkspaceMember(id, { userId, role, permissions: [] }) : await api.removeWorkspaceMember(id, userId)
    if (version !== epoch) return
    selected.value = group; memberId.value = null; notice.value = '成员设置已更新'
  } catch (reason) { if (version === epoch) error.value = detail(reason) }
  finally { if (version === epoch) busy.value = false }
}
async function renameGroup() {
  if (!selected.value || busy.value || !editName.value.trim()) return
  const version = epoch
  busy.value = true; error.value = ''
  try {
    const group = await api.updateWorkspace(selected.value.id, { name: editName.value.trim(), color: selected.value.color })
    if (version !== epoch) return
    selected.value = group; groups.value = groups.value.map(item => item.id === group.id ? group : item); notice.value = '群组名称已更新'
  } catch (reason) { if (version === epoch) error.value = detail(reason) }
  finally { if (version === epoch) busy.value = false }
}
async function leave() {
  if (!selected.value || busy.value || !window.confirm('退出群组后，你将不能继续读取群消息或下载群资料。确认退出？')) return
  const version = epoch
  busy.value = true
  try {
    await api.leaveWorkspace(selected.value.id)
    if (version !== epoch) return
    reset(); await loadGroups()
  } catch (reason) { if (version === epoch) error.value = detail(reason) }
  finally { if (version === epoch) busy.value = false }
}
async function download(file: MessageView['attachments'][number]) {
  const version = epoch
  try { await api.download(file.downloadUrl, file.fileName) }
  catch (reason) { if (version === epoch) error.value = detail(reason) }
}
function startReply(message: MessageView) { reply.value = message; document.getElementById('group-draft')?.focus() }
watch(() => authStore.state.user?.id, () => { reset(); if (authStore.state.user) void loadGroups() }, { flush: 'sync' })
onMounted(loadGroups)
onBeforeUnmount(reset)
async function liveRefresh(){
  const id=selected.value?.id,version=epoch;if(!id||busy.value||loading.value||page.value!==0)return
  try{const result=await api.groupMessages(id);if(version===epoch&&selected.value?.id===id)messages.value=result}
  catch{if(version===epoch){messages.value=[];error.value='群组访问状态已变化，请重新选择群组'}}
}
onMounted(()=>window.addEventListener('pkb:live-update',liveRefresh))
onBeforeUnmount(()=>window.removeEventListener('pkb:live-update',liveRefresh))
</script>

<template>
  <div class="page-stack groups-page">
    <section class="page-intro page-intro--split"><div><p class="page-kicker">GROUPS / COLLABORATION</p><h2>群组协作</h2><p>围绕一个研究主题，一起讨论、回复和分享资料。</p></div><button v-if="canCreate" class="button button--ghost" :disabled="busy" @click="creating = !creating">{{ creating ? '取消新建' : '新建群组' }}</button></section>
    <form v-if="creating" class="group-create" @submit.prevent="createGroup"><label class="field-label">群组名称<input v-model="groupName" class="field-input" maxlength="100" placeholder="例如：多模态论文研读" required :disabled="busy" /></label><button class="button button--dark" :disabled="busy">创建群组</button><small>创建后你是群主，可以添加成员。</small></form>
    <p v-if="error" class="inline-alert inline-alert--error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
    <div class="group-layout">
      <aside class="group-sidebar"><p class="page-kicker">{{ isAdmin ? '全部群组' : '我的群组' }}</p><button v-for="group in groups" :key="group.id" :class="{ active: selected?.id === group.id }" :disabled="busy" @click="selectGroup(group.id)"><strong>{{ group.name }}</strong><small>{{ group.ownerName }} · {{ roles[group.currentRole] || '成员' }}</small></button><p v-if="!groups.length && !loading">还没有加入群组。可以新建一个，或请群主添加你的账号。</p><RouterLink to="/app/mail">联系平台管理员 ↗</RouterLink></aside>
      <main class="group-main" :aria-busy="loading">
        <template v-if="selected">
          <header class="group-header"><div><h3>{{ selected.name }}</h3><p>群主 {{ selected.ownerName }} · {{ roles[selected.currentRole] }}<span v-if="isAdmin"> · 平台管理员</span></p></div><div><button class="button button--ghost" :disabled="busy || loading" @click="selectGroup(selected.id)">刷新</button><button v-if="canLeave" class="button button--ghost" :disabled="busy" @click="leave">退出群组</button></div></header>
          <details class="group-members"><summary>{{ canManage ? '成员与权限' : '查看成员' }} · {{ selected.members.length }} 位可见成员</summary><p>群权限仅在本群生效。成员可交流与分享；只读成员只能查看和下载。群管理员不能修改群主或其他群管理员。</p><div v-for="member in selected.members" :key="member.id" class="group-member"><span><strong>{{ member.displayName }}</strong><small>@{{ member.username }}</small></span><select v-if="canManage && member.role !== 'OWNER' && (canPromote || member.role !== 'ADMIN')" :value="member.role" class="compact-select" :aria-label="`${member.displayName}的群角色`" :disabled="busy" @change="memberAction(member.userId, ($event.target as HTMLSelectElement).value as WorkspaceMemberRole)"><option v-if="canPromote" value="ADMIN">群管理员</option><option value="MEMBER">成员</option><option value="VIEWER">只读成员</option></select><span v-else>{{ roles[member.role] }}</span><RouterLink v-if="member.userId !== authStore.state.user?.id && canWrite" :to="`/app/mail?to=${member.userId}`">私信</RouterLink><button v-if="canManage && member.role !== 'OWNER' && (canPromote || member.role !== 'ADMIN')" class="table-action" :disabled="busy" @click="memberAction(member.userId)">移除</button></div>
            <form v-if="canManage" class="group-add" @submit.prevent="memberId && memberAction(memberId, memberRole)"><label class="field-label">添加成员<select v-model="memberId" class="field-input" required :disabled="busy"><option :value="null" disabled>选择平台用户</option><option v-for="user in directory.filter(user => !selected?.members.some(member => member.userId === user.id))" :key="user.id" :value="user.id">{{ user.displayName }} · @{{ user.username }}</option></select></label><label class="field-label">群角色<select v-model="memberRole" class="field-input" :disabled="busy"><option value="MEMBER">成员</option><option value="VIEWER">只读成员</option><option v-if="canPromote" value="ADMIN">群管理员</option></select></label><button class="button button--ghost" :disabled="busy || !memberId">添加</button></form>
            <form v-if="selected.currentPermissions.includes('SETTINGS_WRITE')" class="group-add" @submit.prevent="renameGroup"><label class="field-label">群组名称<input v-model="editName" class="field-input" required maxlength="100" :disabled="busy" /></label><button class="button button--ghost" :disabled="busy">保存名称</button></form>
          </details>
          <form v-if="canWrite" class="group-compose" @submit.prevent="send"><label class="field-label" for="group-draft">发布到群组</label><p v-if="reply" class="group-reply">回复 {{ reply.senderName }}：{{ reply.body.slice(0, 90) }} <button type="button" :disabled="busy" @click="reply = null">取消回复</button></p><textarea id="group-draft" v-model="text" class="field-input" maxlength="20000" rows="4" placeholder="写下你的问题、发现，或分享一份资料…" :disabled="busy" /><div class="group-compose-actions"><label class="attachment-picker">选择资料<input :key="fileInputKey" type="file" multiple :disabled="busy" @change="chooseFiles" /></label><small>最多 5 个 · 每个 20 MB</small><button class="button button--dark" :disabled="busy || loading || (!text.trim() && !files.length)">{{ busy ? '正在处理…' : '发送到群组' }}</button></div><p v-if="files.length">{{ files.map(file => file.name).join('、') }}</p></form>
          <p v-else class="group-readonly">你在本群没有发言权限。有阅读权限时仍可查看消息与下载资料。</p>
          <p v-if="!messages.length && !loading" class="group-empty">{{ selected.currentPermissions.includes('CONTENT_READ') ? '还没有讨论，从第一条消息开始。' : '你没有阅读本群内容的权限。' }}</p>
          <article v-for="message in messages" :key="message.id" class="group-message"><header><strong>{{ message.senderName }}</strong><time>{{ time(message.createdAt) }}</time><button v-if="canWrite" :disabled="busy" @click="startReply(message)">回复</button></header><small v-if="message.replyToId">回复群内消息 · {{ messages.find(item => item.id === message.replyToId)?.senderName || '较早的讨论' }}</small><p>{{ message.body }}</p><div class="group-files"><button v-for="file in message.attachments" :key="file.id" @click="download(file)">{{ file.fileName }} <small>{{ Math.ceil(file.sizeBytes / 1024) }} KB ↓</small></button></div></article>
          <button v-if="more" class="button button--ghost" :disabled="busy || loading" @click="older">查看更早的消息</button>
        </template>
        <p v-else>{{ loading ? '正在加载群组…' : '从左侧选择一个群组。消息与资料仅向有权限的成员和平台管理员开放。' }}</p>
      </main>
    </div>
  </div>
</template>

<style scoped>
.group-layout{display:grid;grid-template-columns:230px minmax(0,1fr);border-top:1px solid var(--line);min-height:580px}
.group-sidebar{padding:26px 26px 20px 0;border-right:1px solid var(--line)}
.group-sidebar>button{display:block;width:100%;padding:12px 14px;margin:0 0 5px;text-align:left;border:0;background:transparent;color:var(--ink);cursor:pointer}
.group-sidebar>button:hover,.group-sidebar>button.active{background:var(--hover-paper)}
.group-sidebar strong{font-size:14px;font-weight:500}.group-sidebar small{display:block;color:var(--muted);font-size:11px;margin-top:4px}
.group-sidebar>p:not(.page-kicker){font-size:13px}.group-sidebar>a{display:block;margin:25px 14px;color:var(--green);font-size:13px;text-decoration:underline}
.group-main{padding:24px 0 30px 34px;min-width:0}.group-header{display:flex;justify-content:space-between;align-items:center;gap:20px;padding-bottom:22px}
.group-header h3{font-size:22px;font-weight:550;margin:0}.group-header p{font-size:12px;color:var(--muted);margin:7px 0 0}
.group-create,.group-add{display:flex;gap:14px;align-items:end;padding:18px 0}.group-create .field-label{flex:1;max-width:380px}.group-create small{align-self:center;color:var(--muted)}
.group-members{border-block:1px solid var(--line);padding:16px 0;margin-bottom:24px;font-size:13px}.group-members summary{cursor:pointer;color:var(--green)}.group-members>p{color:var(--muted);font-size:12px;margin:16px 0}
.group-member{display:flex;gap:18px;align-items:center;padding:10px 0;border-bottom:1px solid var(--line-soft)}.group-member>span:first-child{flex:1}.group-member strong{font-weight:500}.group-member small{margin-left:12px;color:var(--muted)}.group-member a{color:var(--green);text-decoration:underline}
.group-add>.field-label:first-child{flex:1}.group-add .field-input{min-width:150px}.group-compose{padding-bottom:24px;border-bottom:1px solid var(--line)}.group-compose textarea{width:100%;margin-top:10px;resize:vertical;min-height:110px;font-size:14px}
.group-compose-actions{display:flex;align-items:center;gap:16px;margin-top:12px}.group-compose-actions small{color:var(--muted);font-size:11px}.group-compose-actions>.button{margin-left:auto}
.group-reply{border-left:2px solid var(--green);padding:5px 12px;font-size:12px;overflow-wrap:anywhere}.group-reply button,.group-message header button{border:0;background:none;color:var(--green);text-decoration:underline;cursor:pointer;padding:0 5px}
.group-readonly,.group-empty{font-size:13px;color:var(--muted);padding:20px 0}.group-message{border-bottom:1px solid var(--line);padding:24px 0;overflow-wrap:anywhere}
.group-message header{display:flex;align-items:center;gap:15px;font-size:13px}.group-message header strong{font-weight:550}.group-message time{font-size:11px;color:var(--muted)}.group-message header button{margin-left:auto}.group-message>small{display:block;margin-top:8px;color:var(--muted)}.group-message>p{white-space:pre-wrap;line-height:1.85;font-size:14px;margin:13px 0}
.group-files{display:flex;flex-wrap:wrap;gap:10px}.group-files button{display:flex;align-items:center;gap:18px;padding:10px 14px;border:1px solid var(--line);background:transparent;color:var(--green);cursor:pointer;text-align:left;max-width:100%;overflow-wrap:anywhere}.group-files button:hover{background:var(--hover-paper)}.group-files small{color:var(--muted);white-space:nowrap}
</style>
