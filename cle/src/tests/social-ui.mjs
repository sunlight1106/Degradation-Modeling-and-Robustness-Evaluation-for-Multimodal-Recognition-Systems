// Synthetic accounts and responses only; never contact a live user or provider.
import { createApp, h, nextTick } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import ContactsView from '../views/ContactsView.vue'
import NoteEditorView from '../views/NoteEditorView.vue'
import { authStore } from '../stores/auth.ts'
import { tokenStorage } from '../api/client.ts'
const fixture = document.getElementById('fixture'), passed = []
const assert = (ok, label) => { if (!ok) throw new Error(label); passed.push(label) }
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const wait = async predicate => { for (let i = 0; i < 550; i++) { await sleep(5); await nextTick(); if (predicate()) return } throw new Error('UI wait timed out') }
const button = text => [...fixture.querySelectorAll('button')].find(n => n.textContent.includes(text))
const envelope = data => new Response(JSON.stringify({ success: true, data }), { headers: { 'Content-Type': 'application/json' } })
async function set(node, value) { node.value = value; node.dispatchEvent(new Event('input', { bubbles: true })); await nextTick() }
const identity = 'PKB-0123456789ABCDEF0123456789ABCDEF'
let copied = '', searchedQuery = ''
Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async value => { copied = value } } })
const bob = { remark: '', pinned: false, muted: false, unreadCount: 1, clearedThrough: 0, identityCode: identity, id: 11, userId: 2, username: 'bob', displayName: 'Bob', status: 'ACCEPTED', incoming: false, blockedByMe: false, available: true }
let people = [bob, { ...bob, id: 12, userId: 3, username: 'charlie', displayName: 'Charlie', status: 'PENDING', incoming: true }]
let rows = [{ id: 1, senderId: 2, senderName: 'Bob', body: '<img src=x onerror=alert(1)>', clientId: 'synthetic', createdAt: '2026-10-06T00:00:00Z' }]
let app, deferred, failSend = true, delayMessages = false
const sends = [], saves = [], preferenceSaves = [], readReceipts = []
let clearCalls = 0, latePreference = false, resolvePreference
const confirmDialog = () => [...document.querySelectorAll('[role=alertdialog] button')].at(-1).click()
let note = { id: 'one', title: 'Original', body: 'Original body', tags: [], library: '计算机学习', contentFormat: 'MARKDOWN', status: { code: 'DRAFT', label: '草稿' }, references: [], shareCount: 0, revision: 0, createdAt: '2026-10-06T00:00:00Z', updatedAt: '2026-10-06T00:00:00Z' }
let conflict = false, delaySave = false
window.confirm = () => true
window.fetch = async (url, init = {}) => {
  const path = String(url)
  if (path.includes('/social/people?')) { searchedQuery = new URL(path, 'http://synthetic.test').searchParams.get('q'); return envelope([{ id: 4, identityCode: identity, username: 'dana', displayName: 'Dana' }]) }
  if (path.endsWith('/social/settings')) return envelope({ discoverable: true })
  if (path.endsWith('/social/contacts')) {
    if (init.method === 'POST') { people = [...people, { ...bob, id: 13, userId: 4, displayName: 'Dana', username: 'dana', status: 'PENDING' }]; return envelope(null) }
    return envelope(authStore.state.user?.id === 9 ? [] : people.filter(p => ['ACCEPTED','PENDING'].includes(p.status) || p.blockedByMe))
  }
  if (path.endsWith('/preferences')) {
    const data = JSON.parse(init.body), id = Number(path.split('/').at(-2)); preferenceSaves.push(data)
    people = people.map(p => p.id === id ? { ...p, ...data } : p)
    if (latePreference) return new Promise(resolve => { resolvePreference = () => resolve(envelope(null)) })
    return envelope(null)
  }
  if (path.endsWith('/read')) { readReceipts.push(JSON.parse(init.body)); return envelope(null) }
  if (path.endsWith('/clear-history')) { clearCalls++; people = people.map(p => p.id === 11 ? { ...p, clearedThrough: rows.at(-1)?.id || 0, unreadCount: 0 } : p); rows = []; return envelope(null) }
  if (/\/social\/contacts\/\d+$/.test(path)) {
    const id = Number(path.split('/').at(-1)), action = JSON.parse(init.body).action
    people = people.map(p => p.id !== id ? p : action === 'block' ? { ...p, blockedByMe: true, available: false } : action === 'unblock' ? { ...p, blockedByMe: false, available: true } : { ...p, status: action === 'accept' ? 'ACCEPTED' : 'REMOVED' })
    return envelope(null)
  }
  if (path.includes('/social/contacts/11/messages')) {
    if (init.method === 'POST') {
      const data = JSON.parse(init.body); sends.push(data)
      if (!rows.some(m => m.clientId === data.clientId)) rows.push({ ...data, id: 2, senderId: 1, senderName: 'Me', createdAt: note.updatedAt })
      if (failSend) { failSend = false; throw new TypeError('Synthetic lost response') }
      return envelope(rows.at(-1))
    }
    if (delayMessages) return new Promise(resolve => { deferred = () => resolve(envelope(rows)) })
    return envelope(path.includes('after=1') ? rows.filter(m => m.id > 1) : rows)
  }
  if (path.includes('/social/contacts/12/messages')) return envelope([])
  if (path.endsWith('/notes/one/shares') || path.endsWith('/notes/sources/experiments') || path.includes('/personal-ai/')) return envelope([])
  if (path.endsWith('/notes')) return envelope([note])
  if (path.endsWith('/notes/one')) {
    if (init.method === 'PATCH') {
      const data = JSON.parse(init.body); saves.push(data)
      if (conflict) return new Response(JSON.stringify({ success: false, error: { code: 'NOTE_SYNC_CONFLICT', message: '服务器版本已更新，请下载草稿' } }), { status: 409, headers: { 'Content-Type': 'application/json' } })
      note = { ...note, ...data, tags: [], status: note.status, revision: note.revision + 1 }
      if (delaySave) return new Promise(resolve => { const saved = { ...note }; deferred = () => resolve(envelope(saved)) })
    }
    return envelope(note)
  }
  throw new Error('Unexpected request: ' + path)
}
async function mount(component, path) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/contacts', component: ContactsView }, { path: '/notes', name: 'notes', component: { template: '<div />' } }, { path: '/notes/:id', name: 'note-edit', component: NoteEditorView }, { path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push(path); await router.isReady(); app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(fixture)
}
try {
  authStore.state.user = { id: 1, permissions: ['note:read','note:write'] }; tokenStorage.set('synthetic-social')
  await mount(ContactsView, '/contacts'); await wait(() => fixture.querySelector('.person-select')); button('申请').click(); await wait(() => button('同意'))
  assert(fixture.textContent.includes('新的申请') && !fixture.querySelector('.chat-compose'), 'Pending contacts do not get a chat composer')
  button('同意').click(); await wait(() => !button('同意')); button('联系人').click(); await wait(() => fixture.querySelectorAll('.person-select').length === 2)
  assert(fixture.textContent.includes('Charlie'), 'Accepting an incoming request adds the contact')
  await set(fixture.querySelector('#people-query'), identity); fixture.querySelector('.people-search').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await wait(() => button('申请添加')); assert(searchedQuery === identity && fixture.querySelector('.person-line .identity-code').textContent.includes(identity), 'Full identity codes are searchable and shown on results'); button('申请添加').click(); await wait(() => button('等待处理'))
  assert(button('等待处理').disabled, 'Sent requests cannot be repeatedly submitted')
  button('联系人').click(); await nextTick(); button('Bob').click(); await wait(() => fixture.querySelector('.chat-message'))
  fixture.querySelector('.chat-header [aria-label="复制身份码"]').click(); await wait(() => copied === identity)
  assert(copied === identity, 'Copying a contact identity code keeps its complete value')
  assert(!fixture.querySelector('.chat-message img') && fixture.textContent.includes('<img'), 'Chat markup is rendered as text without executing HTML')
  await set(fixture.querySelector('.chat-compose textarea'), 'hello')
  fixture.querySelector('.chat-compose').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await wait(() => fixture.querySelector('[role=alert]') && !button('发送').disabled)
  assert(fixture.querySelector('.chat-compose textarea').value === 'hello', 'A failed send preserves its draft')
  fixture.querySelector('.chat-compose').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await wait(() => fixture.querySelector('.chat-compose textarea').value === '')
  assert(sends.length === 2 && sends[0].clientId === sends[1].clientId, 'Retry reuses the idempotency key after a lost response')
  assert(fixture.querySelectorAll('.chat-message').length === 2, 'Retry produces one displayed copy of the message')
  assert(readReceipts.some(r => r.through === 1), 'Opening a conversation acknowledges only its loaded messages')
  button('联系人管理').click(); await nextTick()
  await set(fixture.querySelector('#contact-remark'), '我的研究同伴'); button('保存备注').click()
  await wait(() => fixture.querySelector('.chat-header h3').textContent.includes('我的研究同伴'))
  assert(preferenceSaves.at(-1).remark === '我的研究同伴', 'A private remark is saved and displayed without changing the username')
  button('置顶联系人').click(); await wait(() => fixture.querySelector('.person-pin'))
  assert(fixture.querySelector('.person-select').textContent.includes('我的研究同伴'), 'Pinned contacts appear first')
  button('消息免打扰').click(); await wait(() => fixture.querySelector('.chat-state'))
  assert(preferenceSaves.at(-1).muted === true && !fixture.querySelector('.person-select.active .person-unread'), 'Muted conversations hide unread number reminders')
  button('清空聊天记录').click(); await nextTick()
  document.querySelector('[role=alertdialog] button').click(); await nextTick()
  assert(clearCalls === 0 && fixture.querySelectorAll('.chat-message').length === 2, 'Cancelling history clearing leaves messages intact')
  button('清空聊天记录').click(); await nextTick(); confirmDialog(); await wait(() => !document.querySelector('[role=alertdialog]'))
  assert(clearCalls === 1 && !fixture.querySelector('.chat-message'), 'Confirmed history clearing removes only the current view of old messages')
  button('加入黑名单').click(); await nextTick(); confirmDialog(); await wait(() => button('解除拉黑'))
  assert(!fixture.querySelector('.chat-compose') && !fixture.textContent.includes('<img'), 'Blacklisting removes the active conversation and exposes an unblock action')
  button('解除拉黑').click(); await wait(() => !button('解除拉黑')); button('联系人').click(); await nextTick()
  assert(!!button('我的研究同伴'), 'Unblocking returns an accepted contact with personal preferences retained')
  button('申请').click(); await wait(() => button('撤回')); button('撤回').click(); await nextTick(); confirmDialog(); await wait(() => !document.querySelector('[role=alertdialog]'))
  assert(!button('撤回'), 'Withdrawn outgoing requests disappear from the request list')
  button('联系人').click(); await nextTick(); button('我的研究同伴').click(); await wait(() => fixture.querySelector('.chat-compose'))
  button('联系人管理').click(); await nextTick(); button('删除联系人').click(); await nextTick(); confirmDialog(); await wait(() => !document.querySelector('[role=alertdialog]'))
  assert(!button('我的研究同伴') && !fixture.querySelector('.chat-compose'), 'Deleting a contact clears the current conversation')
  button('Charlie').click(); await wait(() => fixture.querySelector('.chat-compose')); button('联系人管理').click(); await nextTick()
  latePreference = true; button('置顶联系人').click(); await wait(() => resolvePreference)
  authStore.state.user = { id: 9, permissions: [] }; tokenStorage.set('synthetic-switched'); await nextTick(); resolvePreference(); await sleep(30)
  assert(!fixture.querySelector('.person-select') && !fixture.querySelector('.contact-manager'), 'Late contact changes cannot restore a previous account contacts or private preferences')
  authStore.state.user = { id: 1, permissions: ['note:read','note:write'] }; tokenStorage.set('synthetic-social')
  app.unmount()
  await mount(NoteEditorView, '/notes/one'); await wait(() => fixture.querySelector('.note-title-input')?.value === 'Original')
  await set(fixture.querySelector('.note-title-input'), 'Auto-saved title')
  await wait(() => saves.length === 1 && fixture.textContent.includes('已保存到服务器'))
  assert(saves[0].baseRevision === 0 && note.title === 'Auto-saved title', 'Editing automatically persists with the loaded server revision')
  delaySave = true; await set(fixture.querySelector('.note-title-input'), 'First in flight')
  await wait(() => saves.length === 2 && deferred)
  await set(fixture.querySelector('.note-title-input'), 'Newer edit while saving')
  delaySave = false; deferred(); deferred = null
  await wait(() => saves.length === 3 && fixture.textContent.includes('已保存到服务器'))
  assert(note.title === 'Newer edit while saving' && saves[2].baseRevision === 2, 'In-flight saves preserve new typing and subsequently save its newer revision')
  conflict = true; await set(fixture.querySelector('.note-title-input'), 'Local conflict draft')
  await wait(() => button('下载当前草稿'))
  const count = saves.length; await sleep(1700)
  assert(saves.length === count && fixture.querySelector('.note-title-input').value === 'Local conflict draft', 'A version conflict stops automatic retries and keeps the local draft')
  assert(!!button('重新加载服务器版本'), 'Conflict offers an explicit server reload alongside draft download')
  conflict = false; delaySave = true; button('重新加载服务器版本').click(); await wait(() => fixture.querySelector('.note-title-input').value === note.title)
  await set(fixture.querySelector('.note-title-input'), 'Account A draft'); await wait(() => deferred)
  tokenStorage.set('synthetic-other-user'); authStore.state.user = { id: 9, permissions: ['note:read','note:write'] }; await nextTick(); deferred(); await sleep(30)
  assert(fixture.querySelector('.note-title-input').value === '', 'Late saves never put the old account private note into a new account')
  document.documentElement.dataset.result = 'passed'; document.getElementById('results').textContent = `PASS (${passed.length} assertions)`
} catch (error) { document.documentElement.dataset.result = 'failed'; document.getElementById('results').textContent = error.stack || String(error) }
finally { app?.unmount(); tokenStorage.clear(); authStore.state.user = null }
