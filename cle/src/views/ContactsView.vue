<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { request } from '@/api/client'
import { socialApi, type Person, type Contact, type ChatMessage, type ContactAction } from '@/api/social'
import { ApiClientError } from '@/api/client'
import { authStore } from '@/stores/auth'
import IdentityCode from '@/components/IdentityCode.vue'
import ReportButton from '@/components/ReportButton.vue'
import AppIcon from '@/components/AppIcon.vue'
import ActionConfirmDialog from '@/components/ActionConfirmDialog.vue'
import { useUnsavedDraft } from '@/lib/useUnsavedDraft'

const historyQuery=ref(''), historyResults=ref<ChatMessage[]>([]),historyPage=ref(0)
const route = useRoute()
let historyVersion = 0
async function searchHistory() {
  const id = selected.value, version = ++historyVersion, choice = selection
  if (!id) return
  historyResults.value = []
  try {
    const rows = await request<ChatMessage[]>(`/social/contacts/${id}/search?${new URLSearchParams({q:historyQuery.value,page:String(historyPage.value)})}`)
    if (version === historyVersion && choice === selection && id === selected.value) historyResults.value = rows
  } catch (e) { if (version === historyVersion && choice === selection) error.value = messageOf(e) }
}
const contacts = ref<Contact[]>([]), results = ref<Person[]>([]), query = ref(''), searched = ref(false)
const selected = ref<number | null>(null), messages = ref<ChatMessage[]>([]), body = ref('')
const error = ref(''), loading = ref(true), searching = ref(false), busy = ref(false), sending = ref(false)
useUnsavedDraft(computed(() => !!body.value.trim()), computed(() => sending.value || busy.value))
const more = ref(false), historyBusy = ref(false), refreshing = ref(false), discoverable = ref(true), transcript = ref<HTMLElement | null>(null)
const listTab = ref<'contacts' | 'requests' | 'blacklist'>('contacts'), managerOpen = ref(false), remarkDraft = ref('')
const confirmation = ref<{ contact: Contact; action: ContactAction | 'clear'; title: string; description: string; label: string } | null>(null)
const acknowledged = new Map<number, number>(), acknowledging = new Set<number>()
const display = (contact: Contact) => contact.remark || contact.displayName
const current = computed(() => contacts.value.find(c => c.id === selected.value))
const friends = computed(() => contacts.value.filter(c => c.status === 'ACCEPTED' && !c.blockedByMe).sort((a, b) => Number(b.pinned) - Number(a.pinned) || display(a).localeCompare(display(b), 'zh-CN')))
const filteredFriends = computed(() => friends.value.filter(c => `${c.remark || ''} ${c.displayName} ${c.username} ${c.identityCode}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const unreadTotal = computed(() => friends.value.reduce((sum, c) => sum + (c.muted || !c.available ? 0 : c.unreadCount || 0), 0))
const incoming = computed(() => contacts.value.filter(c => c.status === 'PENDING' && c.incoming && c.available))
const outgoing = computed(() => contacts.value.filter(c => c.status === 'PENDING' && !c.incoming && c.available))
const blocked = computed(() => contacts.value.filter(c => c.blockedByMe))
let generation = 0, selection = 0, searchVersion = 0, contactsVersion = 0, timer: ReturnType<typeof setTimeout> | undefined
let retry: { id: number; clientId: string; body: string } | null = null
const messageOf = (reason: unknown) => reason instanceof ApiClientError ? reason.message : '连接失败，内容仍保留，请重试。'
function reset() {
  generation++; selection++; searchVersion++; clearTimeout(timer)
  contacts.value = []; results.value = []; messages.value = []; selected.value = null; body.value = ''; query.value = ''
  retry = null; confirmation.value = null; managerOpen.value = false; remarkDraft.value = ''; listTab.value = 'contacts'; acknowledged.clear(); acknowledging.clear(); error.value = ''; loading.value = false; refreshing.value = false; searching.value = false; busy.value = false; sending.value = false; historyBusy.value = false
}
function liveRefresh(){void refresh()}
onMounted(()=>window.addEventListener('pkb:live-update',liveRefresh))
onBeforeUnmount(()=>window.removeEventListener('pkb:live-update',liveRefresh))
watch(selected,()=>{historyVersion++;historyQuery.value='';historyResults.value=[];historyPage.value=0})
watch(historyQuery,()=>{historyVersion++;historyResults.value=[]})
function openNotification() {
  if (route.query.tab === 'requests') listTab.value = 'requests'
  const contact = friends.value.find(c => String(c.id) === route.query.contact)
  if (contact) void open(contact)
}
watch(() => route.query, openNotification)
function schedule() { clearTimeout(timer); if (authStore.state.user) timer = setTimeout(() => void refresh(), 5000) }
function reconcile(items: Contact[]) {
  const oldCleared = current.value?.clearedThrough || 0
  contacts.value = items
  if (selected.value && !friends.value.some(c => c.id === selected.value)) {
    selection++; selected.value = null; messages.value = []; body.value = ''; retry = null; managerOpen.value = false
  } else if (selected.value && (!current.value?.available || (current.value?.clearedThrough || 0) > oldCleared)) {
    selection++; historyVersion++; historyResults.value = []; messages.value = []; more.value = false; retry = null
  }
}
function selectList(tab: typeof listTab.value) { listTab.value = tab; query.value = ''; searched.value = false; results.value = [] }
function toggleManager() { managerOpen.value = !managerOpen.value; if (managerOpen.value) remarkDraft.value = current.value?.remark || '' }
async function markRead() {
  const id = selected.value, through = messages.value.at(-1)?.id, token = generation, choice = selection
  if (!id || !through || acknowledging.has(id) || document.hidden || !current.value?.available || (acknowledged.get(id) || 0) >= through) return
  acknowledging.add(id)
  try {
    await socialApi.read(id, through)
    if (token !== generation || choice !== selection) return
    acknowledged.set(id, Math.max(acknowledged.get(id) || 0, through))
    if (current.value) current.value.unreadCount = 0
  } catch { /* Reading remains usable if acknowledgement fails; retry on the next refresh. */ }
  finally { if (token === generation) acknowledging.delete(id) }
}
function readAtEnd() {
  if (!historyBusy.value && transcript.value && transcript.value.scrollHeight - transcript.value.scrollTop - transcript.value.clientHeight < 100) void markRead()
}
async function load() {
  const token = generation; loading.value = true
  try {
    const [items, settings] = await Promise.all([socialApi.contacts(), socialApi.settings()])
    if (token !== generation) return
    reconcile(items); discoverable.value = settings.discoverable; openNotification()
  } catch (reason) { if (token === generation) error.value = messageOf(reason) }
  finally { if (token === generation) { loading.value = false; schedule() } }
}
async function scrollEnd() { await nextTick(); transcript.value?.scrollTo?.({ top: transcript.value.scrollHeight }) }
function merge(items: ChatMessage[]) { messages.value = Array.from(new Map([...messages.value, ...items].filter(m => m.id > (current.value?.clearedThrough || 0)).map(m => [m.id, m])).values()).sort((a, b) => a.id - b.id) }
async function refresh() {
  if (document.hidden || refreshing.value || loading.value || busy.value || sending.value || historyBusy.value) { schedule(); return }
  const token = generation, choice = selection, id = selected.value, contactEpoch = contactsVersion
  refreshing.value = true
  try {
    const items = await socialApi.contacts()
    if (token !== generation || contactEpoch !== contactsVersion) return
    reconcile(items)
    if (id && choice === selection && current.value?.available) {
      const nearEnd = !transcript.value || transcript.value.scrollHeight - transcript.value.scrollTop - transcript.value.clientHeight < 100
      const rows = await socialApi.messages(id, { after: messages.value.at(-1)?.id || 0 })
      if (token !== generation || choice !== selection) return
      merge(rows); if (nearEnd) { if (rows.length) await scrollEnd(); void markRead() }
    }
  } catch (reason) {
    if (token === generation && choice === selection) {
      error.value = messageOf(reason)
      if (reason instanceof ApiClientError && [401, 403, 404].includes(reason.status)) { messages.value = []; selected.value = null; selection++ }
    }
  } finally { if (token === generation) { refreshing.value = false; schedule() } }
}
async function search() {
  if (query.value.trim().length < 2 || query.value.trim().length > 50) { error.value = '请输入完整身份码，或至少 2 个字符的用户名、昵称'; return }
  const token = generation, version = ++searchVersion, text = query.value.trim()
  searching.value = true; error.value = ''; results.value = []; searched.value = false
  try { const rows = await socialApi.search(text); if (token === generation && version === searchVersion) { results.value = rows; searched.value = true } }
  catch (reason) { if (token === generation && version === searchVersion) error.value = messageOf(reason) }
  finally { if (token === generation && version === searchVersion) searching.value = false }
}
watch(query, () => { searchVersion++; results.value = []; searched.value = false; searching.value = false })
async function mutate(work: () => Promise<unknown>) {
  if (busy.value || sending.value) return false
  const token = generation; contactsVersion++; busy.value = true; error.value = ''
  try { await work(); if (token !== generation) return false; const items = await socialApi.contacts(); if (token !== generation) return false; reconcile(items); return true }
  catch (reason) { if (token === generation) error.value = messageOf(reason); return false }
  finally { if (token === generation) busy.value = false }
}
function act(contact: Contact, action: ContactAction | 'clear') {
  const descriptions = {
    block: ['加入黑名单', `将「${display(contact)}」加入黑名单后，双方无法搜索、私聊或发送站内信。已建立的联系人关系保留，群组权限单独管理。`, '确认拉黑'],
    remove: ['删除联系人', `删除「${display(contact)}」后，双方需要重新申请并同意才能私聊。历史记录不会从对方账户删除。`, '确认删除'],
    withdraw: ['撤回申请', '撤回后，对方无法再接受这份申请。重新申请需要等待 24 小时。', '确认撤回'],
    clear: ['清空聊天记录', '将清空这个会话在你所有设备上的已有聊天记录，无法在当前账户中恢复。对方的记录不受影响，新消息仍会正常接收。', '确认清空'],
  }
  if (action in descriptions) {
    const [title, description, label] = descriptions[action as keyof typeof descriptions]
    error.value = ''; confirmation.value = { contact, action, title: title!, description: description!, label: label! }; return
  }
  void mutate(() => socialApi.act(contact.id, action as ContactAction))
}
async function confirmAction() {
  const pending = confirmation.value, token = generation
  if (!pending) return
  if (await mutate(() => pending.action === 'clear' ? socialApi.clearHistory(pending.contact.id) : socialApi.act(pending.contact.id, pending.action)) && token === generation) {
    confirmation.value = null
    if (pending.action === 'block') selectList('blacklist')
  }
}
function preferences(settings: Partial<Pick<Contact, 'remark' | 'pinned' | 'muted'>>) {
  if (current.value) { const id = current.value.id; void mutate(() => socialApi.preferences(id, settings)) }
}
function relation(person: Person) { return contacts.value.find(c => c.userId === person.id) }
async function open(contact: Contact) {
  if (busy.value || sending.value || contact.id === selected.value) return
  if (body.value.trim() && !window.confirm('切换会话会丢弃未发送的文字，继续？')) return
  const token = generation, choice = ++selection
  managerOpen.value = false; selected.value = contact.id; messages.value = []; body.value = ''; retry = null; more.value = false; historyBusy.value = true; error.value = ''
  if (!contact.available) { historyBusy.value = false; return }
  try { const rows = await socialApi.messages(contact.id); if (token === generation && choice === selection) { messages.value = rows.filter(m => m.id > (current.value?.clearedThrough || 0)); more.value = rows.length === 50; await scrollEnd(); void markRead() } }
  catch (reason) { if (token === generation && choice === selection) error.value = messageOf(reason) }
  finally { if (token === generation && choice === selection) historyBusy.value = false }
}
async function older() {
  if (!selected.value || historyBusy.value || !messages.value.length) return
  const token = generation, choice = selection, height = transcript.value?.scrollHeight || 0
  historyBusy.value = true
  try { const rows = await socialApi.messages(selected.value, { before: messages.value[0]!.id }); if (token === generation && choice === selection) { merge(rows); more.value = rows.length === 50; await nextTick(); if (transcript.value) transcript.value.scrollTop += transcript.value.scrollHeight - height } }
  catch (reason) { if (token === generation && choice === selection) error.value = messageOf(reason) }
  finally { if (token === generation && choice === selection) historyBusy.value = false }
}
async function send() {
  if (busy.value || !selected.value || sending.value || !body.value.trim() || body.value.length > 4000 || !current.value?.available) return
  const token = generation, choice = selection, text = body.value.trim(), id = selected.value
  if (!retry || retry.id !== id || retry.body !== text) retry = { id, body: text, clientId: crypto.randomUUID() }
  const attempt = retry; sending.value = true; error.value = ''
  try {
    const message = await socialApi.send(id, attempt.clientId, text)
    if (token !== generation || choice !== selection) return
    // Poll by the last received cursor rather than advancing it to our send response; this avoids skipping concurrent incoming messages.
    const rows = await socialApi.messages(id, { after: messages.value.at(-1)?.id || 0 })
    if (token !== generation || choice !== selection) return
    merge(rows); if (rows.length < 50) merge([message])
    body.value = ''; retry = null; await scrollEnd(); void markRead()
  } catch (reason) { if (token === generation && choice === selection) error.value = messageOf(reason) }
  finally { if (token === generation && choice === selection) sending.value = false }
}
async function toggleDiscovery(event: Event) {
  const value = (event.target as HTMLInputElement).checked, token = generation
  await mutate(async () => { const saved = await socialApi.discovery(value); if (token === generation) discoverable.value = saved.discoverable })
  ;(event.target as HTMLInputElement).checked = discoverable.value
}
function visibility() { if (!document.hidden) void refresh() }
watch(() => authStore.state.user?.id, () => { reset(); if (authStore.state.user) void load() }, { flush: 'sync' })
onMounted(() => { void load(); document.addEventListener('visibilitychange', visibility) })
onBeforeUnmount(() => { reset(); document.removeEventListener('visibilitychange', visibility) })
</script>

<template>
  <details v-if="selected" class="research-card"><summary>搜索当前会话</summary><form class="research-actions" @submit.prevent="historyPage=0;searchHistory()"><input v-model="historyQuery" class="field-input" maxlength="160" placeholder="输入聊天关键词"/><button class="button button--ghost">搜索</button></form><article v-for="m in historyResults" :key="m.id"><small>{{ m.senderName }} · {{ new Date(m.createdAt).toLocaleString() }}</small><p>{{ m.body }}</p></article><button v-if="historyPage" class="button button--ghost" @click="historyPage--;searchHistory()">上一页</button><button v-if="historyResults.length===30" class="button button--ghost" @click="historyPage++;searchHistory()">下一页</button></details>
  <div class="page-stack people-page">
    <section class="page-intro page-intro--split"><div><p class="page-kicker">PEOPLE & CONVERSATIONS</p><h2>联系人与聊天</h2><p>把交流留在这里，把联系掌握在自己手中。</p></div><RouterLink class="text-link" to="/app/groups">群组讨论与资料 →</RouterLink></section>
    <p v-if="error && !confirmation" class="inline-alert inline-alert--error" role="alert">{{ error }}</p>
    <div class="people-layout">
      <aside class="people-directory">
        <form class="people-search" @submit.prevent="search"><label for="people-query">查找与添加</label><div><input id="people-query" v-model="query" class="field-input" type="search" maxlength="50" placeholder="身份码、用户名或昵称" /><button class="button button--ghost" :disabled="searching || loading">{{ searching ? '查找中…' : '查找用户' }}</button></div></form>
        <nav class="people-tabs" aria-label="联系人分类"><button :class="{ active: listTab === 'contacts' && !searched }" :aria-current="listTab === 'contacts' && !searched ? 'page' : undefined" @click="selectList('contacts')">联系人 <b v-if="unreadTotal">{{ unreadTotal > 99 ? '99+' : unreadTotal }}</b></button><button :class="{ active: listTab === 'requests' && !searched }" :aria-current="listTab === 'requests' && !searched ? 'page' : undefined" @click="selectList('requests')">申请 <b v-if="incoming.length">{{ incoming.length }}</b></button><button :class="{ active: listTab === 'blacklist' && !searched }" :aria-current="listTab === 'blacklist' && !searched ? 'page' : undefined" @click="selectList('blacklist')">黑名单 <small v-if="blocked.length">{{ blocked.length }}</small></button></nav>
        <div class="people-list-content">
          <section v-if="searched || searching" class="people-section"><h3>搜索结果 <small>最多 20 人</small></h3><p v-if="searching" class="people-hint">正在查找…</p><p v-else-if="!results.length" class="people-hint">没有找到用户。可以粘贴完整身份码，或尝试其他用户名。</p><div v-for="person in results" :key="person.id" class="person-line"><span><strong>{{ person.displayName }}</strong><small>@{{ person.username }}</small><IdentityCode :value="person.identityCode" :copyable="false" /></span><button class="button button--ghost button--small" :disabled="busy || !!relation(person)" @click="mutate(() => socialApi.add(person.id))">{{ relation(person)?.status === 'ACCEPTED' ? '已添加' : relation(person) ? '等待处理' : '申请添加' }}</button></div></section>
          <template v-else-if="listTab === 'contacts'">
            <div class="people-section-heading"><span>我的联系人</span><small>{{ friends.length }} 人</small></div>
            <p v-if="loading" class="people-hint">正在读取联系人…</p><div v-else-if="!filteredFriends.length" class="people-empty"><AppIcon name="users" :size="25" /><strong>{{ query ? '没有匹配的联系人' : '还没有联系人' }}</strong><p>在上方输入身份码、用户名或昵称，查找并申请添加。</p></div>
            <button v-for="person in filteredFriends" :key="person.id" class="person-select" :class="{ active: selected === person.id }" :disabled="sending || busy" @click="open(person)"><span class="person-initial">{{ display(person).slice(0, 1) }}</span><span class="person-info"><strong>{{ display(person) }} <small v-if="person.pinned" class="person-pin">置顶</small></strong><small>{{ person.available ? `@${person.username}` : '暂时无法联系' }}</small></span><span v-if="person.muted" class="person-muted" title="消息免打扰" aria-label="消息免打扰"><AppIcon name="bell" :size="14" /></span><b v-else-if="person.available && person.unreadCount" class="person-unread" :aria-label="`${person.unreadCount} 条未读消息`">{{ person.unreadCount > 99 ? '99+' : person.unreadCount }}</b></button>
          </template>
          <template v-else-if="listTab === 'requests'">
            <section class="people-section"><h3>新的申请 <small>{{ incoming.length }}</small></h3><p v-if="!incoming.length" class="people-hint">没有待处理的申请。</p><article v-for="person in incoming" :key="person.id" class="request-card"><strong>{{ person.displayName }}</strong><small>@{{ person.username }}</small><IdentityCode :value="person.identityCode" :copyable="false" /><div class="person-actions"><button :disabled="busy" @click="act(person, 'accept')">同意</button><button :disabled="busy" @click="act(person, 'reject')">拒绝</button><button :disabled="busy" @click="act(person, 'block')">加入黑名单</button></div></article></section>
            <section class="people-section"><h3>已发送的申请 <small>{{ outgoing.length }}</small></h3><p v-if="!outgoing.length" class="people-hint">没有等待回复的申请。</p><div v-for="person in outgoing" :key="person.id" class="person-line"><span><strong>{{ person.displayName }}</strong><small>等待对方同意</small></span><button class="text-link" :disabled="busy" @click="act(person, 'withdraw')">撤回</button></div></section>
          </template>
          <section v-else class="people-section blacklist-section"><h3>黑名单 <small>{{ blocked.length }} 人</small></h3><p class="people-hint">拉黑后，双方无法搜索、私聊或发送站内信。群组权限单独管理。</p><div v-if="!blocked.length" class="people-empty"><AppIcon name="shield" :size="25" /><strong>黑名单为空</strong><p>在联系人管理或新的申请中，可以将对方加入黑名单。</p></div><article v-for="person in blocked" :key="person.id" class="blacklist-card"><div class="person-line"><span><strong>{{ display(person) }}</strong><small>@{{ person.username }}</small></span><button class="button button--ghost button--small" :disabled="busy" @click="act(person, 'unblock')">解除拉黑</button></div><IdentityCode :value="person.identityCode" /><p>{{ person.status === 'ACCEPTED' ? '原有联系人关系保留，双方都解除限制后可继续交流。' : '解除后不会自动添加，需要重新申请并等待对方同意。' }}</p></article></section>
        </div>
        <footer class="people-directory-footer"><label class="people-discovery"><input type="checkbox" :checked="discoverable" :disabled="busy || loading" @change="toggleDiscovery" />允许其他用户搜索到我</label><p>关闭后，身份码也无法被搜索。</p><RouterLink to="/app/mail">站内信与附件 <span>↗</span></RouterLink></footer>
      </aside>
      <section class="chat-panel" aria-label="私聊">
        <template v-if="current">
          <header class="chat-header"><div><h3>{{ display(current) }} <span v-if="current.muted" class="chat-state">免打扰</span></h3><span>{{ current.remark ? `${current.displayName} · ` : '' }}@{{ current.username }}</span><div><IdentityCode :value="current.identityCode" /></div></div><div class="person-actions"><RouterLink v-if="current.available" :to="`/app/mail?to=${current.userId}`">发附件</RouterLink><button class="contact-manage-button" :aria-expanded="managerOpen" aria-controls="contact-manager" @click="toggleManager"><AppIcon name="settings" :size="16" />联系人管理</button></div></header>
          <section v-if="managerOpen" id="contact-manager" class="contact-manager" aria-label="联系人管理">
            <form class="manager-remark" @submit.prevent="preferences({ remark: remarkDraft.trim() })"><label for="contact-remark">备注名 <small>仅自己可见</small></label><div><input id="contact-remark" v-model="remarkDraft" class="field-input" maxlength="80" placeholder="给对方设置一个好记的名字" :disabled="busy" /><button class="button button--ghost button--small" :disabled="busy || sending || remarkDraft.trim() === (current.remark || '')">保存备注</button></div></form>
            <button class="contact-setting" role="switch" :aria-checked="!!current.pinned" :disabled="busy || sending" @click="preferences({ pinned: !current.pinned })"><span><strong>置顶联系人</strong><small>优先显示在联系人列表顶部</small></span><i class="contact-switch" :class="{ on: current.pinned }" aria-hidden="true" /></button>
            <button class="contact-setting" role="switch" :aria-checked="!!current.muted" :disabled="busy || sending" @click="preferences({ muted: !current.muted })"><span><strong>消息免打扰</strong><small>正常接收消息，隐藏未读数字提醒</small></span><i class="contact-switch" :class="{ on: current.muted }" aria-hidden="true" /></button>
            <div class="contact-danger-actions"><button :disabled="busy || sending" @click="act(current, 'clear')">清空聊天记录</button><button :disabled="busy || sending" @click="act(current, 'remove')">删除联系人</button><button :disabled="busy || sending" @click="act(current, 'block')">加入黑名单</button></div>
          </section>
          <template v-if="current.status === 'ACCEPTED' && current.available">
            <div ref="transcript" class="chat-transcript" role="log" aria-label="聊天记录" aria-live="polite" @scroll.passive="readAtEnd"><button v-if="more" class="chat-older text-link" :disabled="historyBusy" @click="older">{{ historyBusy ? '读取中…' : '查看更早消息' }}</button><p v-if="!messages.length" class="chat-welcome">{{ historyBusy ? '正在读取聊天记录…' : '从一句问候开始。' }}</p><article v-for="message in messages" :key="message.id" class="chat-message" :class="{ mine: message.senderId === authStore.state.user?.id }"><header><strong>{{ message.senderId === authStore.state.user?.id ? '我' : display(current) }}</strong><time>{{ new Date(message.createdAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }}</time></header><p>{{ message.body }}</p><ReportButton v-if="message.senderId !== authStore.state.user?.id" type="CHAT" :source-id="message.id" /></article></div>
            <form class="chat-compose" @submit.prevent="send"><textarea v-model="body" aria-label="聊天内容" maxlength="4000" rows="3" placeholder="写下消息…" :disabled="sending || historyBusy || busy" @keydown.ctrl.enter.prevent="send" @keydown.meta.enter.prevent="send" /><footer><span>Ctrl / ⌘ + Enter 发送 · {{ body.length }}/4000</span><button class="button button--dark" :disabled="sending || historyBusy || busy || !body.trim()">{{ sending ? '发送中…' : '发送' }}</button></footer></form>
          </template>
          <div v-else class="chat-empty"><AppIcon name="shield" :size="30" /><h3>当前无法交流</h3><p>联系人关系或账户状态发生了变化。<br />你仍可以管理自己的备注、黑名单与聊天记录。</p></div>
        </template>
        <div v-else class="chat-empty"><AppIcon name="mail" :size="32" /><h3>开始一次交流</h3><p>选择一位联系人，继续对话。<br />在联系人管理中设置备注、免打扰和黑名单。</p><small>笔记保持私密，分享内容由你决定。</small></div>
      </section>
    </div>
    <ActionConfirmDialog v-if="confirmation" :title="confirmation.title" :description="confirmation.description" :confirm-label="confirmation.label" :busy="busy" :error="error" @cancel="confirmation = null" @confirm="confirmAction" />
  </div>
</template>

<style scoped>
.people-layout{display:grid;grid-template-columns:320px minmax(0,1fr);border:1px solid var(--line);background:var(--paper);border-radius:4px;overflow:hidden;min-height:630px;height:clamp(630px,calc(100vh - 340px),920px)}
.people-directory{display:flex;flex-direction:column;min-width:0;min-height:0;border-right:1px solid var(--line);background:var(--canvas)}
.people-search{padding:22px 20px 14px}.people-search label{font-size:12px;font-weight:500}.people-search>div{display:flex;gap:6px;margin-top:10px}.people-search input{min-width:0;font-size:12px;padding:9px}.people-search button{padding:8px 10px;flex-shrink:0;font-size:11px}
.people-tabs{display:flex;gap:2px;padding:0 14px;border-bottom:1px solid var(--line)}.people-tabs button{flex:1;display:flex;align-items:center;justify-content:center;gap:5px;border:0;border-bottom:2px solid transparent;background:none;color:var(--muted);font:inherit;font-size:12px;padding:12px 0;cursor:pointer}.people-tabs button.active{border-bottom-color:var(--green);color:var(--green)}.people-tabs button:hover{color:var(--ink)}.people-tabs b,.person-unread{font-size:10px;font-weight:500;border-radius:9px;background:var(--green);color:var(--paper);min-width:17px;text-align:center;padding:2px 5px}.people-tabs small{font-size:10px}
.people-list-content{flex:1;min-height:0;overflow:auto;padding:8px 12px 18px;scrollbar-width:thin}.people-section-heading{display:flex;justify-content:space-between;font-size:11px;color:var(--muted);padding:14px 10px}.people-section-heading small{font-size:11px}.people-section{padding:12px 8px}.people-section+.people-section{border-top:1px solid var(--line)}.people-section h3{display:flex;justify-content:space-between;font-size:13px;font-weight:550;margin:5px 0 16px}.people-section h3 small{font-weight:400;font-size:11px;color:var(--muted)}.people-hint{font-size:12px;line-height:1.8;color:var(--muted)}
.person-select{display:flex;align-items:center;gap:11px;text-align:left;width:100%;border:1px solid transparent;border-radius:3px;background:transparent;padding:14px 10px;color:var(--ink);cursor:pointer;transition:background .12s}.person-select:hover{background:var(--hover-paper)}.person-select.active{background:var(--green-soft);border-color:var(--line)}.person-select strong{font-size:14px;font-weight:500}.person-select small{display:block;margin-top:5px;font-size:11px;color:var(--muted)}.person-select .person-pin{display:inline;margin-left:5px;font-size:9px}.person-initial{width:34px;height:34px;display:grid;place-items:center;background:var(--paper);border:1px solid var(--line);border-radius:50%;font-size:13px;flex-shrink:0}.person-info{min-width:0;flex:1;overflow-wrap:anywhere}.person-muted{color:var(--muted);display:flex}.person-unread{flex-shrink:0}
.person-line{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:12px 0;font-size:13px}.person-line>span{min-width:0;overflow-wrap:anywhere}.person-line strong,.request-card>strong{font-weight:500}.person-line small,.request-card>small{display:block;margin:5px 0;font-size:11px;color:var(--muted)}.person-line .button{flex-shrink:0;padding:7px 9px;font-size:11px}.person-actions{display:flex;gap:15px;align-items:center;flex-wrap:wrap}.person-actions button,.person-actions a{display:inline-flex;align-items:center;gap:6px;font:inherit;font-size:12px;color:var(--green);border:0;background:transparent;padding:0;cursor:pointer;text-decoration:none}.person-actions button:hover,.person-actions a:hover{color:var(--ink)}.request-card{border-bottom:1px solid var(--line);padding:16px 0;font-size:13px}.request-card .person-actions{margin-top:16px}.blacklist-card{border-bottom:1px solid var(--line);padding-bottom:16px;margin-top:12px}.blacklist-card p{font-size:11px;line-height:1.8;color:var(--muted);margin:10px 0 0}.people-empty{padding:30px 12px;color:var(--muted);font-size:12px;line-height:1.8}.people-empty strong{display:block;margin-top:16px;font-size:14px;font-weight:500;color:var(--ink)}.people-empty p{margin-bottom:0}
.people-directory-footer{padding:18px 20px;border-top:1px solid var(--line)}.people-discovery{display:flex;gap:8px;align-items:center;font-size:11px;color:var(--ink)}.people-discovery input{accent-color:var(--green)}.people-directory-footer p{font-size:10px;color:var(--muted);margin:8px 0 20px}.people-directory-footer a{display:flex;justify-content:space-between;color:var(--green);text-decoration:none;font-size:12px}
.chat-panel{display:flex;flex-direction:column;min-width:0;min-height:0}.chat-header{padding:22px 28px;border-bottom:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;gap:20px}.chat-header>div{min-width:0}.chat-header h3{font-size:19px;font-weight:550;margin:0 0 6px;overflow-wrap:anywhere}.chat-header span{font-size:11px;color:var(--muted)}.chat-header .chat-state{font-size:10px;font-weight:400;margin-left:8px;background:var(--canvas);padding:3px 6px}.chat-header .identity-code{margin-top:5px}.chat-header .contact-manage-button{padding:8px 10px;border:1px solid var(--line);border-radius:3px}.contact-manage-button[aria-expanded=true]{background:var(--green-soft)}
.contact-manager{display:grid;grid-template-columns:1fr 1fr;gap:18px 28px;padding:20px 28px;border-bottom:1px solid var(--line);background:var(--canvas);flex-shrink:0;max-height:50%;overflow:auto}.manager-remark{grid-column:1/-1}.manager-remark label{font-size:12px}.manager-remark small{font-size:10px;color:var(--muted);margin-left:10px}.manager-remark>div{display:flex;gap:10px;margin-top:10px}.manager-remark input{font-size:12px;min-width:0;max-width:340px}.manager-remark button{flex-shrink:0;font-size:11px}.contact-setting{display:flex;align-items:center;justify-content:space-between;gap:15px;border:0;background:transparent;padding:0;color:var(--ink);text-align:left;cursor:pointer}.contact-setting strong{font-size:12px;font-weight:500;line-height:1.6}.contact-setting small{display:block;font-size:10px;color:var(--muted);margin-top:5px}.contact-switch{width:30px;height:17px;border-radius:10px;border:1px solid var(--line);background:var(--paper);position:relative;flex-shrink:0}.contact-switch:after{content:'';position:absolute;left:3px;top:3px;width:9px;height:9px;border-radius:50%;background:var(--muted);transition:transform .12s}.contact-switch.on{background:var(--green);border-color:var(--green)}.contact-switch.on:after{transform:translateX(13px);background:var(--paper)}.contact-danger-actions{grid-column:1/-1;border-top:1px solid var(--line);padding-top:16px;display:flex;gap:24px;flex-wrap:wrap}.contact-danger-actions button{font:inherit;font-size:11px;border:0;padding:0;background:none;color:var(--muted);cursor:pointer}.contact-danger-actions button:hover{color:#aa5860}
.chat-transcript{flex:1;min-height:80px;overflow:auto;padding:24px 28px;overscroll-behavior:contain;scrollbar-width:thin}.chat-message{margin:0 0 24px;max-width:82%;width:fit-content}.chat-message.mine{margin-left:auto}.chat-message header{display:flex;align-items:center;gap:12px;font-size:10px;color:var(--muted);margin-bottom:8px}.chat-message header strong{font-weight:400}.chat-message.mine header{justify-content:flex-end}.chat-message p{white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px;line-height:1.8;background:var(--canvas);padding:11px 15px;border:1px solid var(--line);border-radius:4px;margin:0}.chat-message.mine p{background:var(--green-soft)}.chat-compose{border-top:1px solid var(--line);padding:16px 28px;flex-shrink:0}.chat-compose textarea{width:100%;resize:none;height:68px;background:transparent;border:0;color:var(--ink);font:inherit;font-size:14px;line-height:1.7;outline-offset:4px}.chat-compose footer{display:flex;justify-content:space-between;align-items:center;margin-top:10px}.chat-compose footer span{font-size:10px;color:var(--muted)}.chat-compose .button{font-size:12px;padding:9px 23px}.chat-empty{margin:auto;text-align:center;padding:35px;color:var(--green)}.chat-empty h3{font-size:24px;font-weight:500;margin:20px 0 15px;color:var(--ink)}.chat-empty p{font-size:13px;line-height:2;color:var(--muted)}.chat-empty small{display:block;font-size:11px;color:var(--muted);margin-top:25px}.chat-welcome{text-align:center;color:var(--muted);font-size:13px;padding:35px 0}.chat-older{display:block;margin:0 auto 22px;font-size:12px}.people-page button:disabled{opacity:.5;cursor:default}
@media(prefers-reduced-motion:reduce){.person-select,.contact-switch:after{transition:none}}
</style>
