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
const bob = { identityCode: identity, id: 11, userId: 2, username: 'bob', displayName: 'Bob', status: 'ACCEPTED', incoming: false, blockedByMe: false, available: true }
let people = [bob, { ...bob, id: 12, userId: 3, username: 'charlie', displayName: 'Charlie', status: 'PENDING', incoming: true }]
let rows = [{ id: 1, senderId: 2, senderName: 'Bob', body: '<img src=x onerror=alert(1)>', clientId: 'synthetic', createdAt: '2026-10-06T00:00:00Z' }]
let app, deferred, failSend = true, delayMessages = false
const sends = [], saves = []
let note = { id: 'one', title: 'Original', body: 'Original body', tags: [], library: '计算机学习', contentFormat: 'MARKDOWN', status: { code: 'DRAFT', label: '草稿' }, references: [], shareCount: 0, revision: 0, createdAt: '2026-10-06T00:00:00Z', updatedAt: '2026-10-06T00:00:00Z' }
let conflict = false, delaySave = false
window.confirm = () => true
window.fetch = async (url, init = {}) => {
  const path = String(url)
  if (path.includes('/social/people?')) { searchedQuery = new URL(path, 'http://synthetic.test').searchParams.get('q'); return envelope([{ id: 4, identityCode: identity, username: 'dana', displayName: 'Dana' }]) }
  if (path.endsWith('/social/settings')) return envelope({ discoverable: true })
  if (path.endsWith('/social/contacts')) {
    if (init.method === 'POST') { people = [...people, { ...bob, id: 13, userId: 4, displayName: 'Dana', username: 'dana', status: 'PENDING' }]; return envelope(null) }
    return envelope(people)
  }
  if (/\/social\/contacts\/\d+$/.test(path)) {
    const id = Number(path.split('/').at(-1)), action = JSON.parse(init.body).action
    people = people.map(p => p.id !== id ? p : { ...p, status: action === 'accept' ? 'ACCEPTED' : 'REMOVED', blockedByMe: action === 'block', available: action === 'accept' })
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
  await mount(ContactsView, '/contacts'); await wait(() => button('同意'))
  assert(fixture.textContent.includes('新的申请') && !fixture.querySelector('.chat-compose'), 'Pending contacts do not get a chat composer')
  button('同意').click(); await wait(() => fixture.querySelectorAll('.person-select').length === 2)
  assert(fixture.textContent.includes('Charlie'), 'Accepting an incoming request adds the contact')
  await set(fixture.querySelector('#people-query'), identity); fixture.querySelector('.people-search').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await wait(() => button('申请添加')); assert(searchedQuery === identity && fixture.querySelector('.person-line .identity-code').textContent.includes(identity), 'Full identity codes are searchable and shown on results'); button('申请添加').click(); await wait(() => button('等待处理'))
  assert(button('等待处理').disabled, 'Sent requests cannot be repeatedly submitted')
  button('Bob').click(); await wait(() => fixture.querySelector('.chat-message'))
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
  button('屏蔽').click(); await wait(() => !fixture.querySelector('.chat-compose'))
  assert(!fixture.textContent.includes('<img'), 'Blocking clears the conversation from the view')
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
