<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { socialApi, type Person, type Contact, type ChatMessage, type ContactAction } from '@/api/social'
import { ApiClientError } from '@/api/client'
import { authStore } from '@/stores/auth'
import IdentityCode from '@/components/IdentityCode.vue'

const contacts = ref<Contact[]>([]), results = ref<Person[]>([]), query = ref(''), searched = ref(false)
const selected = ref<number | null>(null), messages = ref<ChatMessage[]>([]), body = ref('')
const error = ref(''), loading = ref(true), searching = ref(false), busy = ref(false), sending = ref(false)
const more = ref(false), historyBusy = ref(false), refreshing = ref(false), discoverable = ref(true), transcript = ref<HTMLElement | null>(null)
const current = computed(() => contacts.value.find(c => c.id === selected.value))
const friends = computed(() => contacts.value.filter(c => c.status === 'ACCEPTED' && c.available))
const incoming = computed(() => contacts.value.filter(c => c.status === 'PENDING' && c.incoming && c.available))
const outgoing = computed(() => contacts.value.filter(c => c.status === 'PENDING' && !c.incoming && c.available))
const blocked = computed(() => contacts.value.filter(c => c.blockedByMe))
let generation = 0, selection = 0, searchVersion = 0, contactsVersion = 0, timer: ReturnType<typeof setTimeout> | undefined
let retry: { id: number; clientId: string; body: string } | null = null
const messageOf = (reason: unknown) => reason instanceof ApiClientError ? reason.message : '连接失败，内容仍保留，请重试。'
function reset() {
  generation++; selection++; searchVersion++; clearTimeout(timer)
  contacts.value = []; results.value = []; messages.value = []; selected.value = null; body.value = ''; query.value = ''
  retry = null; error.value = ''; loading.value = false; refreshing.value = false; searching.value = false; busy.value = false; sending.value = false; historyBusy.value = false
}
function schedule() { clearTimeout(timer); if (authStore.state.user) timer = setTimeout(() => void refresh(), 5000) }
function reconcile(items: Contact[]) {
  contacts.value = items
  if (selected.value && !friends.value.some(c => c.id === selected.value)) { selection++; selected.value = null; messages.value = []; body.value = ''; retry = null }
}
async function load() {
  const token = generation; loading.value = true
  try {
    const [items, settings] = await Promise.all([socialApi.contacts(), socialApi.settings()])
    if (token !== generation) return
    reconcile(items); discoverable.value = settings.discoverable
  } catch (reason) { if (token === generation) error.value = messageOf(reason) }
  finally { if (token === generation) { loading.value = false; schedule() } }
}
async function scrollEnd() { await nextTick(); transcript.value?.scrollTo?.({ top: transcript.value.scrollHeight }) }
function merge(items: ChatMessage[]) { messages.value = Array.from(new Map([...messages.value, ...items].map(m => [m.id, m])).values()).sort((a, b) => a.id - b.id) }
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
      merge(rows); if (rows.length && nearEnd) void scrollEnd()
    }
  } catch (reason) {
    if (token === generation && choice === selection) {
      error.value = messageOf(reason)
      if (reason instanceof ApiClientError && [401, 403, 404].includes(reason.status)) { messages.value = []; selected.value = null; selection++ }
    }
  } finally { if (token === generation) { refreshing.value = false; schedule() } }
}
async function search() {
  if (query.value.trim().length < 2 || query.value.trim().length > 50) { error.value = '请输入至少 2 个字符的用户名或昵称前缀'; return }
  const token = generation, version = ++searchVersion, text = query.value.trim()
  searching.value = true; error.value = ''; results.value = []; searched.value = false
  try { const rows = await socialApi.search(text); if (token === generation && version === searchVersion) { results.value = rows; searched.value = true } }
  catch (reason) { if (token === generation && version === searchVersion) error.value = messageOf(reason) }
  finally { if (token === generation && version === searchVersion) searching.value = false }
}
watch(query, () => { searchVersion++; results.value = []; searched.value = false; searching.value = false })
async function mutate(work: () => Promise<unknown>) {
  if (busy.value) return
  const token = generation; contactsVersion++; busy.value = true; error.value = ''
  try { await work(); if (token !== generation) return; const items = await socialApi.contacts(); if (token === generation) reconcile(items) }
  catch (reason) { if (token === generation) error.value = messageOf(reason) }
  finally { if (token === generation) busy.value = false }
}
function act(contact: Contact, action: ContactAction) {
  if (['remove', 'block'].includes(action) && !window.confirm(action === 'block' ? '屏蔽后双方不能再私聊或发送站内信。群组成员权限不受影响。继续？' : '删除联系人后需重新申请才能私聊。继续？')) return
  void mutate(() => socialApi.act(contact.id, action))
}
function relation(person: Person) { return contacts.value.find(c => c.userId === person.id) }
async function open(contact: Contact) {
  if (sending.value || contact.id === selected.value) return
  if (body.value.trim() && !window.confirm('切换会话会丢弃未发送的文字，继续？')) return
  const token = generation, choice = ++selection
  selected.value = contact.id; messages.value = []; body.value = ''; retry = null; more.value = false; historyBusy.value = true; error.value = ''
  try { const rows = await socialApi.messages(contact.id); if (token === generation && choice === selection) { messages.value = rows; more.value = rows.length === 50; void scrollEnd() } }
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
  if (!selected.value || sending.value || !body.value.trim() || body.value.length > 4000 || !current.value?.available) return
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
    body.value = ''; retry = null; void scrollEnd()
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
  <div class="page-stack people-page">
    <section class="page-intro page-intro--split"><div><p class="page-kicker">PEOPLE & CONVERSATIONS</p><h2>联系人与聊天</h2><p>找到一起学习的人。同意添加后，开启属于你们的对话。</p></div><RouterLink class="text-link" to="/app/groups">群组讨论与资料 →</RouterLink></section>
    <p v-if="error" class="inline-alert inline-alert--error" role="alert">{{ error }}</p>
    <div class="people-layout">
      <aside class="people-directory">
        <form class="people-search" @submit.prevent="search"><label for="people-query">查找用户</label><div><input id="people-query" v-model="query" class="field-input" type="search" maxlength="50" placeholder="身份码 / 用户名 / 昵称" /><button class="button button--ghost" :disabled="searching || loading">{{ searching ? '查找中…' : '搜索' }}</button></div></form>
        <label class="people-discovery"><input type="checkbox" :checked="discoverable" :disabled="busy || loading" @change="toggleDiscovery" />允许其他登录用户搜索到我</label>
        <p class="people-hint">输入完整身份码可精确查找；用户名或昵称至少输入 2 个字。关闭上方选项后，身份码也无法被搜索。</p>
        <section v-if="searched || results.length" class="people-section"><h3>搜索结果 <small>最多 20 人</small></h3><p v-if="!results.length" class="people-hint">没有找到可添加的用户，试试完整身份码或用户名。</p><div v-for="person in results" :key="person.id" class="person-line"><span><strong>{{ person.displayName }}</strong><small>@{{ person.username }}</small><IdentityCode :value="person.identityCode" :copyable="false" /></span><button class="button button--ghost button--small" :disabled="busy || !!relation(person)" @click="mutate(() => socialApi.add(person.id))">{{ relation(person)?.status === 'ACCEPTED' ? '已添加' : relation(person) ? '等待处理' : '申请添加' }}</button></div></section>
        <section v-if="incoming.length" class="people-section"><h3>新的申请 <small>{{ incoming.length }}</small></h3><div v-for="person in incoming" :key="person.id" class="person-line"><span><strong>{{ person.displayName }}</strong><small>@{{ person.username }}</small><IdentityCode :value="person.identityCode" :copyable="false" /></span><div class="person-actions"><button :disabled="busy" @click="act(person, 'accept')">同意</button><button :disabled="busy" @click="act(person, 'reject')">拒绝</button><button :disabled="busy" @click="act(person, 'block')">屏蔽</button></div></div></section>
        <section class="people-section"><h3>我的联系人 <small>{{ friends.length }}</small></h3><p v-if="loading" class="people-hint">正在读取联系人…</p><p v-else-if="!friends.length" class="people-hint">从搜索开始，发送你的第一份添加申请。</p><button v-for="person in friends" :key="person.id" class="person-select" :class="{ active: selected === person.id }" :disabled="sending" @click="open(person)"><span class="person-initial">{{ person.displayName.slice(0, 1) }}</span><span><strong>{{ person.displayName }}</strong><small>@{{ person.username }}</small><IdentityCode :value="person.identityCode" :copyable="false" /></span><span class="person-arrow">↗</span></button></section>
        <details v-if="outgoing.length" class="people-section"><summary>已发送的申请 · {{ outgoing.length }}</summary><div v-for="person in outgoing" :key="person.id" class="person-line"><span>{{ person.displayName }}<small>等待对方同意</small></span><button class="text-link" :disabled="busy" @click="act(person, 'remove')">撤回</button></div></details>
        <details v-if="blocked.length" class="people-section"><summary>已屏蔽 · {{ blocked.length }}</summary><div v-for="person in blocked" :key="person.id" class="person-line"><span>{{ person.displayName }}</span><button class="text-link" :disabled="busy" @click="act(person, 'unblock')">解除屏蔽</button></div><p class="people-hint">解除后不会自动恢复联系人；24 小时后可重新申请。</p></details>
        <RouterLink class="people-admin-link text-link" to="/app/mail">联系平台管理员 / 发送附件 →</RouterLink>
      </aside>
      <section class="chat-panel" aria-label="私聊">
        <template v-if="current && current.status === 'ACCEPTED' && current.available">
          <header class="chat-header"><div><h3>{{ current.displayName }}</h3><span>@{{ current.username }} · 消息保存在服务器</span><div><IdentityCode :value="current.identityCode" /></div></div><div class="person-actions"><RouterLink :to="`/app/mail?to=${current.userId}`">发附件</RouterLink><button :disabled="busy || sending" @click="act(current, 'remove')">删除联系人</button><button :disabled="busy || sending" @click="act(current, 'block')">屏蔽</button></div></header>
          <div ref="transcript" class="chat-transcript" role="log" aria-label="聊天记录" aria-live="polite">
            <button v-if="more" class="chat-older text-link" :disabled="historyBusy" @click="older">{{ historyBusy ? '读取中…' : '查看更早消息' }}</button>
            <p v-if="!messages.length" class="chat-welcome">{{ historyBusy ? '正在读取聊天记录…' : '从一句问候开始。' }}</p>
            <article v-for="message in messages" :key="message.id" class="chat-message" :class="{ mine: message.senderId === authStore.state.user?.id }"><header><strong>{{ message.senderName }}</strong><time>{{ new Date(message.createdAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }}</time></header><p>{{ message.body }}</p></article>
          </div>
          <form class="chat-compose" @submit.prevent="send"><textarea v-model="body" aria-label="聊天内容" maxlength="4000" rows="3" placeholder="写下消息…" :disabled="sending || historyBusy" @keydown.ctrl.enter.prevent="send" @keydown.meta.enter.prevent="send" /><footer><span>Ctrl / ⌘ + Enter 发送 · {{ body.length }}/4000</span><button class="button button--dark" :disabled="sending || historyBusy || !body.trim()">{{ sending ? '发送中…' : '发送' }}</button></footer></form>
        </template>
        <div v-else class="chat-empty"><span>hello, there.</span><h3>让知识，也连接彼此。</h3><p>在左侧选择联系人开始聊天。<br />笔记保持私密，分享链接由你决定。</p><small>页面打开时约每 5 秒检查新消息；离线消息下次打开可读取。</small></div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.people-layout{display:grid;grid-template-columns:340px minmax(0,1fr);border-top:1px solid var(--line);min-height:640px}.people-directory{padding:26px 26px 26px 0;border-right:1px solid var(--line);max-height:calc(100vh - 355px);min-height:520px;overflow:auto;scrollbar-width:thin}.people-search label,.people-section h3,.people-section summary{font-size:13px;font-weight:600}.people-search>div{display:flex;gap:8px;margin:10px 0 14px}.people-search input{min-width:0;font-size:12px}.people-search button{padding:8px 12px;flex-shrink:0}.people-discovery{display:flex;gap:9px;align-items:center;font-size:12px;color:var(--ink)}.people-hint{font-size:12px;line-height:1.8;color:var(--muted)}.people-section{border-top:1px solid var(--line);padding-top:18px;margin-top:22px}.people-section h3{display:flex;justify-content:space-between;margin:0 0 14px}.people-section small{color:var(--muted);font-weight:400}.person-line{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:12px 0;font-size:13px}.person-line span,.person-select>span:nth-child(2){min-width:0;overflow-wrap:anywhere}.person-line small,.person-select small{display:block;margin-top:4px;font-size:11px;color:var(--muted)}.person-select{display:flex;align-items:center;gap:12px;text-align:left;width:100%;border:0;background:transparent;padding:13px 10px;color:var(--ink);cursor:pointer}.person-select:hover,.person-select.active{background:var(--hover-paper)}.person-select strong{font-size:13px;font-weight:500}.person-initial{width:32px;height:32px;display:grid;place-items:center;background:var(--paper);border:1px solid var(--line);border-radius:50%;font-size:12px;flex-shrink:0}.person-arrow{margin-left:auto;color:var(--muted)}.person-actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.person-actions button,.person-actions a{font:inherit;font-size:12px;color:var(--green);border:0;background:transparent;padding:0;cursor:pointer}.person-actions button:hover,.person-actions a:hover{text-decoration:underline}.person-actions button:disabled{opacity:.45;cursor:default}.people-admin-link{display:block;margin-top:32px;font-size:12px}.chat-panel{display:flex;flex-direction:column;min-width:0;height:clamp(520px,calc(100vh - 355px),780px);min-height:520px}.chat-header{padding:24px 30px;border-bottom:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;gap:20px}.chat-header h3{font-size:18px;margin:0 0 6px}.chat-header span{font-size:12px;color:var(--muted)}.chat-transcript{flex:1;overflow:auto;padding:22px 30px;overscroll-behavior:contain}.chat-message{margin:0 0 24px;max-width:82%;width:fit-content}.chat-message.mine{margin-left:auto}.chat-message header{display:flex;align-items:center;gap:12px;font-size:11px;color:var(--muted);margin-bottom:7px}.chat-message header strong{font-weight:400}.chat-message.mine header{justify-content:flex-end}.chat-message p{white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px;line-height:1.8;background:var(--hover-paper);padding:12px 16px;border-radius:3px;margin:0}.chat-message.mine p{border:1px solid var(--line);background:var(--paper)}.chat-compose{border-top:1px solid var(--line);padding:18px 30px}.chat-compose textarea{width:100%;resize:vertical;min-height:72px;max-height:180px;background:transparent;border:0;color:var(--ink);font:inherit;font-size:14px;line-height:1.7;outline-offset:4px}.chat-compose footer{display:flex;justify-content:space-between;align-items:center;margin-top:14px}.chat-compose footer span{font-size:11px;color:var(--muted)}.chat-empty{margin:auto;text-align:center;padding:48px}.chat-empty>span{font:14px ui-monospace,monospace;color:var(--green)}.chat-empty h3{font-size:26px;font-weight:500;margin:20px 0 15px}.chat-empty p{font-size:14px;line-height:2;color:var(--muted)}.chat-empty small{display:block;font-size:11px;color:var(--muted);margin-top:35px}.chat-welcome{text-align:center;color:var(--muted);font-size:13px;padding-top:45px}.chat-older{display:block;margin:0 auto 22px;font-size:12px}
</style>
