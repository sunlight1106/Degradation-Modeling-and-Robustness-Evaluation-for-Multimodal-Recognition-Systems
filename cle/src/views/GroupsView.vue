<script setup lang="ts">
import GroupLifecycle from '@/components/GroupLifecycle.vue'
import GroupResources from '@/components/GroupResources.vue'
import GroupInvitations from '@/components/GroupInvitations.vue'
import GroupMessageActions from '@/components/GroupMessageActions.vue'
import { request } from '@/api/client'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import { authStore } from '@/stores/auth'
import { groupApi, type GroupFeatures, type GroupPerson } from '@/api/groups'
import { createRequestGuard } from '@/lib/requestGuard'
import type { MessageView, WorkspaceMemberRole, WorkspaceView } from '@/types/api'

const groups = ref<WorkspaceView[]>([])
const route = useRoute()
const selected = ref<WorkspaceView | null>(null)
const messages = ref<MessageView[]>([])
const directory = ref<GroupPerson[]>([])
const features = ref<Record<number, GroupFeatures>>({}), groupQuery = ref('')
const peopleQuery = ref(''), peoplePage = ref(0), peopleMore = ref(false), peopleBusy = ref(false), pickerOpen = ref(false)
const searchSender=ref(''),searchFrom=ref(''),searchTo=ref(''),searchAttached=ref(false)
const searchQuery = ref(''), searchResults = ref<MessageView[]>([]), searchOpen = ref(false), searchPage = ref(0), searchMore = ref(false), searchBusy = ref(false)
const announcementDraft = ref(''), editingAnnouncement = ref(false)
const announcementRevision = ref(0)
const peopleGuard = createRequestGuard(), searchGuard = createRequestGuard()
let peopleTimer: ReturnType<typeof setTimeout> | undefined
const currentFeatures = computed(() => selected.value ? features.value[selected.value.id] : undefined)
const visibleGroups = computed(() => groups.value.filter(g => g.name.toLowerCase().includes(groupQuery.value.trim().toLowerCase())).slice().sort((a,b) => Number(features.value[b.id]?.pinned) - Number(features.value[a.id]?.pinned)))
const timeline = computed(() => messages.value.slice().sort((a,b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id)))
const loading = ref(false), busy = ref(false), creating = ref(false)
const error = ref(''), notice = ref(''), groupName = ref(''), text = ref('')
const editName = ref('')
const files = ref<File[]>([]), fileInputKey = ref(0)
const reply = ref<MessageView | null>(null)
const memberId = ref<number | null>(null), memberRole = ref<WorkspaceMemberRole>('MEMBER')
const page = ref(0), more = ref(false)
let epoch = 0, refreshing = false
const isAdmin = computed(() => authStore.state.user?.roleCode === 'ADMIN')
const canCreate = computed(() => isAdmin.value || authStore.has('workspace:manage'))
const archived = ref(false)
const canWrite = computed(() => !archived.value && canRead.value && selected.value?.currentPermissions.includes('CONTENT_WRITE'))
const canRead = computed(() => selected.value?.currentPermissions.includes('CONTENT_READ') && authStore.has('message:read'))
const canSettings = computed(() => selected.value?.currentPermissions.includes('SETTINGS_WRITE') && canRead.value)
const canManage = computed(() => selected.value?.currentPermissions.includes('MEMBERS_WRITE'))
const canPromote = computed(() => isAdmin.value || selected.value?.ownerId === authStore.state.user?.id)
const canLeave = computed(() => selected.value?.ownerId !== authStore.state.user?.id && selected.value?.members.some(member => member.userId === authStore.state.user?.id))
const roles: Record<WorkspaceMemberRole, string> = { OWNER: '群主', ADMIN: '群管理员', MEMBER: '成员', VIEWER: '只读成员' }
const detail = (reason: unknown) => reason instanceof ApiClientError ? reason.message : '操作失败，请重试'
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })

function clearDraft() { text.value = ''; files.value = []; reply.value = null; fileInputKey.value++ }
function reset() { epoch++; peopleGuard.cancel(); searchGuard.cancel(); clearTimeout(peopleTimer); groupQuery.value = ''; features.value = {}; groups.value = []; selected.value = null; messages.value = []; directory.value = []; clearDraft(); error.value = ''; notice.value = ''; busy.value = false; loading.value = false; memberId.value = null; groupName.value = ''; creating.value = false }
function storeFeatures(value: GroupFeatures) { features.value = { ...features.value, [value.groupId]: value } }
async function markRead(id: number, version: number) {
  const latest = timeline.value.at(-1)
  if (!latest || !canRead.value || document.visibilityState === 'hidden') return
  try { const result = await groupApi.read(id, latest.id); if (version === epoch) storeFeatures(result) }
  catch (reason) { if (version === epoch) error.value = detail(reason) }
}
async function loadGroups() {
  const version = ++epoch
  loading.value = true; error.value = ''
  try {
    const result = await groupApi.overview()
    if (version !== epoch) return
    groups.value = result.groups
    features.value = Object.fromEntries(result.features.map(item => [item.groupId,item]))
    if (result.groups.length) { const requested = result.groups.find(g => g.id === Number(route.query.group)); await selectGroup(requested?.id ?? (visibleGroups.value[0] || result.groups[0]).id) }
  } catch (reason) { if (version === epoch) error.value = detail(reason) }
  finally { if (version === epoch) loading.value = false }
}
async function selectGroup(id: number) {
  const sameGroup = selected.value?.id === id
  const version = ++epoch
  peopleGuard.cancel(); searchGuard.cancel(); clearTimeout(peopleTimer); pickerOpen.value = false; peopleBusy.value = false; searchBusy.value = false
  searchOpen.value = false; searchResults.value = []; searchQuery.value = ''; searchSender.value='';searchFrom.value='';searchTo.value='';searchAttached.value=false; peopleQuery.value = ''; editingAnnouncement.value = false
  archived.value = false; selected.value = null; messages.value = []; directory.value = []; page.value = 0; more.value = false
  memberId.value = null; memberRole.value = 'MEMBER'; loading.value = true; error.value = ''; notice.value = ''
  if (!sameGroup) clearDraft()
  try {
    const [group, metadata] = await Promise.all([groupApi.detail(id), groupApi.features(id)])
    if (version !== epoch) return
    if (!group) throw new Error('该群组已不可访问')
    selected.value = group
    groups.value = groups.value.some(item => item.id === id) ? groups.value.map(item => item.id === id ? group : item) : [...groups.value, group]; storeFeatures(metadata)
    editName.value = group.name
    if (canRead.value) {
      const result = await api.groupMessages(id)
      if (version !== epoch) return
      messages.value = result; more.value = result.length === 50
      await markRead(id, version)
    }
  } catch (reason) {
    if (version === epoch) { error.value = reason instanceof Error ? reason.message : detail(reason); selected.value = null; messages.value = []; directory.value = []; clearDraft() }
  } finally { if (version === epoch) loading.value = false }
}
async function findPeople(append = false) {
  if (!selected.value || !canManage.value || busy.value) return
  const request = peopleGuard.start(), id = selected.value.id, version = epoch, nextPage = append ? peoplePage.value + 1 : 0
  peopleBusy.value = true; if (!append) directory.value = []
  try { const result = await groupApi.people(id, peopleQuery.value, nextPage, request.signal); if (request.current() && version === epoch) { directory.value = append ? [...directory.value, ...result.items] : result.items; peoplePage.value = nextPage; peopleMore.value = result.hasMore } }
  catch (reason) { if (request.current() && version === epoch) error.value = detail(reason) }
  finally { if (request.current() && version === epoch) peopleBusy.value = false }
}
watch(peopleQuery, () => { memberId.value = null; peopleGuard.cancel(); directory.value = []; clearTimeout(peopleTimer); if (pickerOpen.value) peopleTimer = setTimeout(() => void findPeople(), 250) })
async function preferences(kind: 'pinned' | 'muted') {
  if (!selected.value || !currentFeatures.value || busy.value) return
  const id = selected.value.id, version = epoch, f = currentFeatures.value
  busy.value = true; error.value = ''
  try { const result = await groupApi.preferences(id, kind === 'pinned' ? !f.pinned : f.pinned, kind === 'muted' ? !f.muted : f.muted); if (version === epoch) storeFeatures(result) }
  catch (reason) { if (version === epoch) error.value = detail(reason) }
  finally { if (version === epoch) busy.value = false }
}
async function updateNotice(messageId?: string | null) {
  if (!selected.value || !currentFeatures.value || !canSettings.value || busy.value) return
  const id = selected.value.id, version = epoch, revision = messageId === undefined ? announcementRevision.value : currentFeatures.value.revision
  busy.value = true; error.value = ''
  try { const result = messageId === undefined ? await groupApi.announcement(id, announcementDraft.value, revision) : await groupApi.pin(id, messageId, revision); if (version === epoch) { storeFeatures(result); editingAnnouncement.value = false } }
  catch (reason) { if (version === epoch) error.value = detail(reason) }
  finally { if (version === epoch) busy.value = false }
}
function editAnnouncement() {
  if (!editingAnnouncement.value) { announcementDraft.value = currentFeatures.value?.announcement || ''; announcementRevision.value = currentFeatures.value?.revision || 0 }
  editingAnnouncement.value = !editingAnnouncement.value
}
async function searchMessages(append = false) {
  if (!selected.value || !canRead.value) return
  const request = searchGuard.start(), id = selected.value.id, version = epoch, nextPage = append ? searchPage.value + 1 : 0
  searchBusy.value = true; searchOpen.value = true; if (!append) searchResults.value = []
  try { const result = await groupApi.search(id, searchQuery.value, nextPage, request.signal,{senderId:searchSender.value?Number(searchSender.value):undefined,after:searchFrom.value?new Date(searchFrom.value+'T00:00:00').toISOString():undefined,before:searchTo.value?new Date(new Date(searchTo.value+'T00:00:00').setDate(new Date(searchTo.value+'T00:00:00').getDate()+1)).toISOString():undefined,attached:searchAttached.value}); if (request.current() && version === epoch) { searchResults.value = append ? [...searchResults.value, ...result] : result; searchPage.value = nextPage; searchMore.value = result.length === 50 } }
  catch (reason) { if (request.current() && version === epoch) error.value = detail(reason) }
  finally { if (request.current() && version === epoch) searchBusy.value = false }
}
function closeSearch() { searchGuard.cancel(); searchBusy.value = false; searchOpen.value = false; searchResults.value = [] }
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
    await markRead(id, version)
  } catch (reason) { if (version === epoch) error.value = detail(reason) }
  finally { if (version === epoch) busy.value = false }
}
async function memberAction(userId: number, role?: WorkspaceMemberRole) {
  if (!selected.value || busy.value) return
  if (!role && !window.confirm('移除此成员？移除后将不能继续访问本群消息和资料。')) return
  const version = epoch, id = selected.value.id
  busy.value = true; error.value = ''
  try {
    if(role && !selected.value.members.some(m=>m.userId===userId)) { await request(`/workspaces/${id}/invitations`,{method:'POST',body:JSON.stringify({targetId:userId,role})});if(version===epoch){memberId.value=null;pickerOpen.value=false;directory.value=[];notice.value='邀请已发送，等待对方确认'};return }
    const group = role ? await api.upsertWorkspaceMember(id, { userId, role, permissions: [] }) : await api.removeWorkspaceMember(id, userId)
    if (version !== epoch) return
    selected.value = group; memberId.value = null; pickerOpen.value = false; directory.value = []; notice.value = '成员设置已更新'
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
watch(() => route.query.group, value => { const id = Number(value); if (groups.value.some(g => g.id === id) && selected.value?.id !== id) void selectGroup(id) })
onBeforeUnmount(reset)
async function liveRefresh(){
  const id=selected.value?.id,version=epoch;if(busy.value||loading.value||refreshing)return
  refreshing=true
  try{const overview=await groupApi.overview();if(version!==epoch)return;groups.value=overview.groups;features.value=Object.fromEntries(overview.features.map(item=>[item.groupId,item]));if(!id)return;const fresh=overview.groups.find(g=>g.id===id);if(!fresh){selected.value=null;messages.value=[];clearDraft();return}selected.value=fresh;if(!canRead.value){messages.value=[];return}if(page.value!==0)return;const result=await api.groupMessages(id);if(version===epoch&&selected.value?.id===id){messages.value=result;await markRead(id,version)}}
  catch{if(version===epoch){messages.value=[];error.value='群组访问状态已变化，请重新选择群组'}}
  finally{refreshing=false}
}
onMounted(()=>window.addEventListener('pkb:live-update',liveRefresh))
onBeforeUnmount(()=>window.removeEventListener('pkb:live-update',liveRefresh))
</script>

<template>
  <div class="page-stack groups-page">
    <section class="page-intro page-intro--split"><div><p class="page-kicker">CONVERSATIONS</p><h2>群组</h2><p>讨论问题，分享资料，把有用的内容留在一起。</p></div><button v-if="canCreate" class="button button--ghost" :disabled="busy" @click="creating = !creating">{{ creating ? '取消新建' : '新建群组' }}</button></section>
    <form v-if="creating" class="group-create" @submit.prevent="createGroup"><label class="field-label">群组名称<input v-model="groupName" class="field-input" maxlength="100" required :disabled="busy" placeholder="例如：论文研读" /></label><button class="button button--dark" :disabled="busy">创建群组</button></form>
    <p v-if="error" class="inline-alert inline-alert--error" role="alert">{{ error }}</p><p v-if="notice" class="field-hint" role="status">{{ notice }}</p>
    <GroupInvitations @refresh="loadGroups" /><div class="group-layout">
      <aside class="group-sidebar"><p class="page-kicker">{{ isAdmin ? '全部群组' : '我的会话' }}</p><input v-model="groupQuery" type="search" class="field-input group-filter" aria-label="筛选群组" placeholder="搜索群组" /><button class="group-refresh" :disabled="busy || loading" @click="loadGroups">刷新列表</button>
        <button v-for="group in visibleGroups" :key="group.id" :class="{ active: selected?.id === group.id }" :disabled="busy" @click="selectGroup(group.id)"><strong>{{ group.name }}</strong><span v-if="features[group.id]?.unread" class="unread-badge">{{ features[group.id].unread > 99 ? '99+' : features[group.id].unread }}</span><small>{{ features[group.id]?.pinned ? '置顶 · ' : '' }}{{ features[group.id]?.muted ? '免打扰 · ' : '' }}{{ group.ownerName }}</small></button>
        <p v-if="!visibleGroups.length && !loading">{{ groups.length ? '没有匹配的群组。' : '还没有群组。新建一个，或请群主通过身份码添加你。' }}</p><RouterLink to="/app/mail">联系管理员 ↗</RouterLink>
      </aside>
      <main class="group-main" :aria-busy="loading"><template v-if="selected">
        <GroupLifecycle :key="selected.id" :group="selected" :owner="canPromote" :manager="!!canSettings" @state="archived=$event.archived" @refresh="selectGroup(selected.id)" /><header class="group-header"><div><h3>{{ selected.name }}</h3><p>{{ selected.members.length }} 位可见成员 · {{ roles[selected.currentRole] }}<span v-if="isAdmin"> · 平台管理员</span></p></div><button class="table-action" :disabled="busy || loading" @click="selectGroup(selected.id)">刷新</button></header>
        <div class="group-toolbar"><button :disabled="busy" @click="preferences('pinned')">{{ currentFeatures?.pinned ? '取消置顶会话' : '置顶会话' }}</button><button :disabled="busy" @click="preferences('muted')">{{ currentFeatures?.muted ? '关闭免打扰' : '免打扰' }}</button><button v-if="canRead" @click="searchOpen ? closeSearch() : searchOpen = true">{{ searchOpen ? '关闭搜索' : '查找消息' }}</button><button v-if="canLeave" :disabled="busy" @click="leave">退出群组</button></div>
        <section v-if="canRead && (currentFeatures?.announcement || canSettings)" class="group-announcement"><header><span class="page-kicker">群公告</span><button v-if="canSettings" class="table-action" :disabled="busy" @click="editAnnouncement">{{ editingAnnouncement ? '取消' : '编辑' }}</button></header><p v-if="!editingAnnouncement">{{ currentFeatures?.announcement || '暂未发布公告。' }}</p><form v-else @submit.prevent="updateNotice()"><textarea v-model="announcementDraft" class="field-input" rows="3" maxlength="2000" aria-label="群公告内容" :disabled="busy" /><button class="button button--ghost button--small" :disabled="busy">保存公告</button></form></section>
        <section v-if="canRead && currentFeatures?.pinnedMessage" class="group-pin"><span class="page-kicker">置顶消息 · {{ currentFeatures.pinnedMessage.senderName }}</span><p>{{ currentFeatures.pinnedMessage.body }}</p><button v-if="canSettings" class="table-action" :disabled="busy" @click="updateNotice(null)">取消置顶消息</button></section>
        <details class="group-members"><summary>{{ canManage ? '成员与权限' : '查看成员' }} · {{ selected.members.length }}</summary><p>权限仅在本群生效。只读成员可查看和下载；群管理员不能修改群主及其他群管理员。</p>
          <div v-for="member in selected.members" :key="member.id" class="group-member"><span><strong>{{ member.displayName }}</strong><small>@{{ member.username }}</small></span><select v-if="canManage && member.role !== 'OWNER' && (canPromote || member.role !== 'ADMIN')" :value="member.role" class="compact-select" :aria-label="`${member.displayName}的群角色`" :disabled="busy" @change="memberAction(member.userId, ($event.target as HTMLSelectElement).value as WorkspaceMemberRole)"><option v-if="canPromote" value="ADMIN">群管理员</option><option value="MEMBER">成员</option><option value="VIEWER">只读成员</option></select><span v-else>{{ roles[member.role] }}</span><RouterLink v-if="member.userId !== authStore.state.user?.id && canWrite" :to="`/app/mail?to=${member.userId}`">私信</RouterLink><button v-if="canManage && member.role !== 'OWNER' && (canPromote || member.role !== 'ADMIN')" class="table-action" :disabled="busy" @click="memberAction(member.userId)">移除</button></div>
          <form v-if="canManage" class="group-add" @submit.prevent="memberId && memberAction(memberId, memberRole)"><div class="group-people field-label"><label for="group-person-search">添加成员</label><input id="group-person-search" v-model="peopleQuery" class="field-input" type="search" maxlength="100" role="combobox" :aria-expanded="pickerOpen" aria-controls="group-people-list" placeholder="姓名、用户名或唯一身份码" :disabled="busy" @focus="pickerOpen = true; findPeople()" @keydown.esc="pickerOpen = false" /><div v-if="pickerOpen" id="group-people-list" class="group-people-menu" role="listbox" aria-label="候选成员"><p v-if="peopleBusy">正在查找…</p><button v-for="person in directory" :key="person.id" type="button" role="option" :aria-selected="memberId === person.id" @click="memberId = person.id"><strong>{{ person.displayName }}</strong><small>{{ person.identityCode }}</small></button><p v-if="!peopleBusy && !directory.length">没有匹配用户。</p><button v-if="peopleMore" type="button" :disabled="peopleBusy" @click="findPeople(true)">更多用户</button><button type="button" @click="pickerOpen = false">收起</button></div><small v-if="memberId">已选 {{ directory.find(p => p.id === memberId)?.displayName }} · {{ directory.find(p => p.id === memberId)?.identityCode }}</small></div><label class="field-label">角色<select v-model="memberRole" class="field-input" :disabled="busy"><option value="MEMBER">成员</option><option value="VIEWER">只读成员</option><option v-if="canPromote" value="ADMIN">群管理员</option></select></label><button class="button button--ghost" :disabled="busy || !memberId">添加</button></form>
          <form v-if="canSettings" class="group-add" @submit.prevent="renameGroup"><label class="field-label">群组名称<input v-model="editName" class="field-input" required maxlength="100" :disabled="busy" /></label><button class="button button--ghost" :disabled="busy">保存名称</button></form>
        </details>
        <GroupResources v-if="canRead" :key="selected.id" :group-id="selected.id" />
        <section v-if="searchOpen && canRead" class="group-search"><form @submit.prevent="searchMessages()"><input v-model="searchQuery" class="field-input" type="search" maxlength="100" aria-label="搜索群内消息" placeholder="输入消息关键词" /><button class="button button--ghost" :disabled="searchBusy">搜索</button></form><div class="group-search-filters"><label>发送者<select v-model="searchSender" class="field-input"><option value="">全部成员</option><option v-for="person in selected.members" :key="person.userId" :value="person.userId">{{person.displayName}}</option></select></label><label>开始日期<input v-model="searchFrom" class="field-input" type="date" /></label><label>结束日期<input v-model="searchTo" class="field-input" type="date" /></label><label><input v-model="searchAttached" type="checkbox" />含附件</label></div><p class="field-hint">日期按本机时区；可只选发送者、日期或附件，不填关键词。</p><article v-for="message in searchResults" :key="message.id" class="search-result"><strong>{{ message.senderName }}</strong><time>{{ time(message.createdAt) }}</time><p>{{ message.body }}</p></article><p v-if="!searchBusy && searchQuery && !searchResults.length">暂无匹配消息。</p><button v-if="searchMore" class="table-action" :disabled="searchBusy" @click="searchMessages(true)">更多结果</button></section>
        <div v-else class="group-thread"><button v-if="more" class="table-action" :disabled="busy || loading" @click="older">查看更早的消息</button><p v-if="!messages.length && !loading" class="group-empty">{{ canRead ? '还没有讨论，从第一条消息开始。' : '你没有阅读本群内容的权限。' }}</p>
          <article v-for="message in timeline" :key="message.id" class="group-message" :class="{ 'group-message--own': message.senderId === authStore.state.user?.id }"><header><span class="message-avatar">{{ message.senderName.slice(0,1) }}</span><strong>{{ message.senderName }}</strong><time>{{ time(message.createdAt) }}</time><button v-if="canWrite" :disabled="busy" @click="startReply(message)">回复</button><button v-if="canSettings" :disabled="busy" @click="updateNotice(message.id)">置顶</button></header><small v-if="message.replyToId" class="reply-context">回复 {{ messages.find(item => item.id === message.replyToId)?.senderName || '较早的讨论' }} · {{ messages.find(item => item.id === message.replyToId)?.body.slice(0,100) }}</small><p>{{ message.body }}</p><div class="group-files"><button v-for="file in message.attachments" :key="file.id" @click="download(file)">{{ file.fileName }} <small>{{ Math.ceil(file.sizeBytes / 1024) }} KB ↓</small></button></div><GroupMessageActions :group-id="selected.id" :message-id="message.id" :can-recall="!!canSettings||(message.senderId===authStore.state.user?.id&&Date.now()-Date.parse(message.createdAt)<600000)" :recalled="message.body==='消息已撤回'" @refresh="selectGroup(selected.id)" /></article>
        </div>
        <form v-if="canWrite" class="group-compose" @submit.prevent="send"><label class="field-label" for="group-draft">发送消息</label><p v-if="reply" class="group-reply">回复 {{ reply.senderName }}：{{ reply.body.slice(0,90) }} <button type="button" :disabled="busy" @click="reply = null">取消回复</button></p><textarea id="group-draft" v-model="text" class="field-input" maxlength="20000" rows="3" placeholder="写下问题，或分享一份资料…" :disabled="busy" @keydown.ctrl.enter.prevent="send" /><div class="group-compose-actions"><label class="attachment-picker">添加资料<input :key="fileInputKey" type="file" multiple :disabled="busy" @change="chooseFiles" /></label><small>5 个 / 每个 20 MB · Ctrl + Enter 发送</small><button class="button button--dark" :disabled="busy || loading || (!text.trim() && !files.length)">{{ busy ? '正在处理…' : '发送到群组' }}</button></div><ul v-if="files.length" class="group-draft-files"><li v-for="(file,index) in files" :key="index">{{ file.name }}<button type="button" :disabled="busy" :aria-label="`移除 ${file.name}`" @click="files.splice(index,1)">×</button></li></ul></form><p v-else class="group-readonly">本群为只读，消息和资料按你的权限显示。</p>
      </template><p v-else>{{ loading ? '正在加载…' : '从左侧选择一个群组。' }}</p></main>
    </div>
  </div>
</template>

<style scoped>
.group-search-filters{display:grid;grid-template-columns:1fr 1fr 1fr auto;align-items:end;gap:12px;margin:12px 0}.group-search-filters label{font-size:12px;display:grid;gap:6px}.group-search-filters input[type=checkbox]{width:auto}

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

.group-sidebar strong,.group-header h3{overflow-wrap:anywhere}.group-header>div{min-width:0}.group-header .table-action{flex-shrink:0}.group-filter{width:100%;margin:12px 0 20px;font-size:13px}.group-sidebar>button.group-refresh{padding:0 0 12px;color:var(--muted);font-size:11px}.group-sidebar>button{position:relative;padding-right:40px}.unread-badge{position:absolute;right:10px;top:14px;min-width:20px;text-align:center;padding:2px 5px;border-radius:12px;background:var(--green);color:var(--paper);font-size:10px}
.group-toolbar{display:flex;gap:20px;padding:0 0 22px}.group-toolbar button{border:0;background:transparent;color:var(--muted);cursor:pointer;font-size:12px}.group-toolbar button:hover{color:var(--green)}
.group-announcement,.group-pin{border-left:2px solid var(--green);padding:12px 18px;background:var(--hover-paper);margin-bottom:20px}.group-announcement header{display:flex;justify-content:space-between}.group-announcement p,.group-pin p{white-space:pre-wrap;font-size:13px;line-height:1.8;overflow-wrap:anywhere}.group-announcement textarea{width:100%;margin:12px 0}.group-pin p{max-height:160px;overflow:auto}
.group-thread{max-height:650px;overflow:auto;padding:0 10px 24px 0;scrollbar-width:thin}.group-thread>.table-action{display:block;margin:0 auto 12px}.group-message{border:0;padding:16px 0}.message-avatar{width:28px;height:28px;display:grid;place-items:center;background:var(--hover-paper);color:var(--green);font-size:12px;border-radius:6px}.group-message header button{margin-left:0}.group-message header time{margin-right:auto}.group-message>p{margin:9px 0 0 43px;padding:12px 16px;background:var(--hover-paper);width:fit-content;max-width:90%;border-radius:3px}.group-message--own>p{background:var(--vocab-soft,var(--hover-paper))}.group-files,.reply-context{margin-left:43px}.group-compose{border-top:1px solid var(--line);border-bottom:0;padding-top:18px;padding-bottom:0}.group-compose textarea{min-height:80px}.group-draft-files{list-style:none;padding:0;display:flex;gap:10px;flex-wrap:wrap;font-size:12px}.group-draft-files button{background:none;border:0;color:var(--green);cursor:pointer}
.group-people{position:relative;min-width:260px}.group-people-menu{position:absolute;top:78px;left:0;right:0;background:var(--paper);border:1px solid var(--line);box-shadow:0 12px 30px #0001;max-height:290px;overflow:auto;z-index:5;padding:6px}.group-people-menu button{display:block;text-align:left;width:100%;padding:10px;border:0;background:transparent;color:var(--ink);cursor:pointer}.group-people-menu button:hover,.group-people-menu button[aria-selected=true]{background:var(--hover-paper)}.group-people-menu strong{font-size:13px;font-weight:500}.group-people-menu small{display:block;font:10px ui-monospace,monospace;color:var(--muted);overflow-wrap:anywhere;margin-top:4px}.group-people-menu p{font-size:12px;padding:10px}.group-search{min-height:200px;padding-bottom:24px}.group-search form{display:flex;gap:12px}.group-search input{flex:1}.search-result{border-bottom:1px solid var(--line);padding:12px 0;font-size:13px}.search-result time{font-size:11px;color:var(--muted);margin-left:12px}.search-result p{white-space:pre-wrap;overflow-wrap:anywhere}.group-sidebar>a,.group-member a,.group-message header button,.group-reply button{text-decoration:none}.group-sidebar>a:hover,.group-member a:hover{color:var(--ink)}

</style>
