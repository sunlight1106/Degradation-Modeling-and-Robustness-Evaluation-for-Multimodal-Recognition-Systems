import { createApp, h, nextTick, reactive } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { adminApi } from '../api/admin'
import { api, ApiClientError, tokenStorage, request, fetchBlob } from '../api/client'
import { workflowApi } from '../api/workflows'
import { groupApi } from '../api/groups'
import { socialApi } from '../api/social'
import { authStore } from '../stores/auth'
import { toastStore } from '../stores/toast'
import AdminTrendChart from '../components/AdminTrendChart.vue'
import AdministrationView from '../views/AdministrationView.vue'
import NotesView from '../views/NotesView.vue'
import MailboxView from '../views/MailboxView.vue'
import GroupsView from '../views/GroupsView.vue'
import ContactsView from '../views/ContactsView.vue'
import LoginView from '../views/LoginView.vue'
import RegisterView from '../views/RegisterView.vue'

const fixture = document.getElementById('fixture'), checks = []
const assert = (value, message) => { if (!value) throw new Error(message); checks.push(message) }
const wait = async predicate => { for (let i = 0; i < 160; i++) { await new Promise(resolve => setTimeout(resolve, 5)); await nextTick(); if (predicate()) return } throw new Error('Interaction did not settle') }
const button = text => [...fixture.querySelectorAll('button')].find(element => element.textContent.includes(text))
const set = async (element, value) => { element.value = value; element.dispatchEvent(new Event('input', { bubbles: true })); element.dispatchEvent(new Event('change', { bubbles: true })); await nextTick() }
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const day = date => ({ date, registrations: 2, loginUsers: 3, notes: 4, aiCalls: 5, experiments: 6 })
const summary = date => ({ users: 2, enabledUsers: 2, disabledUsers: 0, expiredUsers: 0, recentUsers: 1, roles: 2, notes: 4, files: 3, storageBytes: 1024, aiCalls: 5, experiments: 6, days: [day(date)], zone: 'UTC', generatedAt: '2026-10-10T00:00:00Z' })
const records = operatorName => ({ total: 2, size: 1, items: [{ id: operatorName, operatorName, createdAt: '2026-10-10T00:00:00Z', action: 'USER_UPDATE', targetType: 'USER', targetId: 2, detail: '{}' }] })
const original = { api: { ...api }, admin: { ...adminApi }, workflows: { ...workflowApi }, groups: { ...groupApi }, social: { ...socialApi }, login: authStore.login, confirm: window.confirm, success: toastStore.success, error: toastStore.error, fetch: window.fetch }
// jsdom has no SVG focus implementation; real focus and CSS behavior are checked in Chromium.
const svgFocus = SVGElement.prototype.focus, svgBlur = SVGElement.prototype.blur
const nativeTimeout = globalThis.setTimeout
SVGElement.prototype.focus = function () {}
SVGElement.prototype.blur = function () { this.dispatchEvent(new Event('blur')) }
let app
async function mountRoute(component, path = '/subject') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path, name: 'notes', component }, { path: '/away', component: { render: () => h('p', 'Destination') } },
    { path: '/new', name: 'note-create', component: { render: () => h('div') } },
    { path: '/edit/:id', name: 'note-edit', component: { render: () => h('div') } },
    { path: '/knowledge', name: 'knowledge', component: { render: () => h('div') } },
    { path: '/:pathMatch(.*)*', component: { render: () => h('div') } },
  ] })
  await router.push(path); await router.isReady()
  app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(fixture)
  await nextTick(); return router
}
try {
  const chart = reactive({ days: [day('2026-10-09'), day('2026-10-10')] })
  app = createApp({ render: () => h(AdminTrendChart, chart) }); app.mount(fixture)
  let points = fixture.querySelectorAll('[data-day]')
  points[0].dispatchEvent(new Event('mouseenter')); await nextTick()
  assert(fixture.querySelector('.chart-readout').textContent.includes('2026-10-09'), 'Hover reads the date')
  points[0].click?.(); fixture.querySelector('svg').dispatchEvent(new Event('mouseleave')); await nextTick()
  assert(!fixture.querySelector('.chart-band') && !fixture.querySelector('.chart-cursor'), 'Mouse leave removes both the band and the cursor after a click')
  assert(points[0].querySelector('rect').getAttribute('fill') === 'transparent', 'Hit targets remain transparent')
  points[0].dispatchEvent(new window.KeyboardEvent('keydown', { key: 'End', bubbles: true })); await nextTick()
  assert(fixture.querySelector('.chart-readout').textContent.includes('2026-10-10'), 'End selects the final date')
  points[1].dispatchEvent(new window.KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })); await nextTick()
  assert(fixture.querySelector('.chart-readout').textContent.includes('2026-10-10'), 'Keyboard movement is clamped at the last date')
  points[1].dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); await nextTick()
  assert(!fixture.querySelector('.chart-cursor'), 'Escape clears keyboard inspection')
  points[0].dispatchEvent(new Event('mouseenter')); await nextTick()
  const svg = fixture.querySelector('svg')
  assert([...svg.children].indexOf(fixture.querySelector('.chart-band')) < [...svg.children].indexOf(fixture.querySelector('polyline')), 'Highlight is drawn behind the curves')
  chart.days = [day('2026-10-11')]; await nextTick()
  assert(!fixture.querySelector('.chart-cursor'), 'Replacing data clears the previous inspection')
  assert([...fixture.querySelectorAll('circle')].every(circle => circle.getAttribute('cx') === '499' && circle.getAttribute('r') === '3'), 'One-day data is visible and centered')
  button('新增用户').click(); button('登录用户').click(); await nextTick()
  assert(!fixture.querySelector('polyline') && [...fixture.querySelectorAll('[data-day]')].every(point => point.tabIndex === -1), 'Turning off every metric removes misleading points and tab stops')
  chart.days = []; await nextTick()
  assert(!fixture.querySelector('svg') && fixture.textContent.includes('暂无数据'), 'Empty results do not leave a stale chart')
  app.unmount()

  authStore.state.user = { id: 301, roleCode: 'ADMIN', permissions: ['admin:stats', 'admin:audit', 'note:read', 'note:write'] }
  adminApi.statistics = async () => summary('initial-range')
  adminApi.audit = async () => records('initial-page')
  await mountRoute(AdministrationView); await wait(() => fixture.querySelector('.admin-chart'))
  const seven = deferred(), ninety = deferred()
  adminApi.statistics = range => range === 7 ? seven.promise : ninety.promise
  const range = fixture.querySelector('select')
  await set(range, '7'); await set(range, '90')
  assert(!fixture.querySelector('.admin-chart'), 'Changing the range hides data from the old range while waiting')
  ninety.resolve(summary('newest-range')); await wait(() => fixture.querySelector('.admin-chart'))
  seven.resolve(summary('stale-range')); await nextTick(); await nextTick()
  assert(fixture.querySelector('.admin-chart').textContent.includes('newest-range') && !fixture.textContent.includes('stale-range'), 'Reversed network responses cannot overwrite the latest range')
  adminApi.statistics = async () => { throw new ApiClientError('TEMPORARY', 'Temporarily unavailable', 503) }
  await set(range, '7'); await wait(() => fixture.querySelector('[role=alert]'))
  assert(!fixture.querySelector('.admin-chart') && fixture.textContent.includes('此时间范围尚未加载'), 'A failed range shows an error instead of incorrectly labeled old data')
  adminApi.statistics = async () => summary('retry-range')
  button('刷新数据').click(); await wait(() => fixture.querySelector('.admin-chart'))
  assert(fixture.textContent.includes('retry-range'), 'Retry restores the requested range')
  adminApi.audit = async () => { throw new Error('Audit failed') }
  button('下一页').click(); await wait(() => fixture.querySelector('[role=alert]'))
  assert(!fixture.querySelector('.data-table tbody') && fixture.textContent.includes('此页尚未加载'), 'A failed audit page never displays rows from the previous page')
  adminApi.audit = async () => records('second-page')
  button('刷新数据').click(); await wait(() => fixture.textContent.includes('second-page'))
  assert(fixture.textContent.includes('第 2 页'), 'Retry preserves audit pagination')
  app.unmount()

  const note = { id: 'synthetic-note', title: 'Synthetic note', excerpt: 'Fixture', library: '综合学习', status: { code: 'DRAFT', label: '草稿' }, tags: [], updatedAt: '2026-10-10T00:00:00Z' }
  api.notes = async () => [note]; workflowApi.due = async () => []
  let confirmations = 0, deletes = 0, notices = 0, removal = deferred()
  window.confirm = () => { confirmations++; return true }
  toastStore.success = () => { notices++ }; toastStore.error = () => {}
  api.deleteNote = () => { deletes++; return removal.promise }
  await mountRoute(NotesView); await wait(() => fixture.querySelector('[aria-label="删除 Synthetic note"]'))
  const deleteButton = fixture.querySelector('[aria-label="删除 Synthetic note"]')
  deleteButton.click(); deleteButton.click(); await nextTick()
  assert(deletes === 1 && confirmations === 1 && deleteButton.disabled, 'Repeated delete clicks make one request and one confirmation')
  removal.reject(new ApiClientError('TEMPORARY', 'Delete failed', 503)); await wait(() => !deleteButton.disabled)
  assert(fixture.querySelector('.learning-note-row'), 'Failed deletion leaves the note available for retry')
  removal = deferred(); deleteButton.click(); await nextTick(); app.unmount(); removal.resolve(); await nextTick(); await nextTick()
  assert(notices === 0, 'Deletion finishing after navigation cannot display a stale success or reload')
  api.notes = async () => [note]
  await mountRoute(NotesView); await wait(() => fixture.querySelector('.learning-note-row'))
  api.notes = async () => { throw new ApiClientError('TEMPORARY', 'Search unavailable', 503) }
  await set(fixture.querySelector('[aria-label=搜索笔记]'), 'different-query'); await wait(() => fixture.textContent.includes('Search unavailable'))
  assert(!fixture.querySelector('.learning-note-row'), 'Failed search clears results from the previous query')
  api.notes = async () => [note]; button('重试').click(); await wait(() => fixture.querySelector('.learning-note-row'))
  assert(fixture.querySelector('[aria-label=搜索笔记]').value === 'different-query', 'Retry keeps the search text')
  app.unmount()

  const contact = { id: 302, displayName: 'Synthetic admin', username: 'synthetic-admin', identityCode: 'PKB-SYNTHETIC', relationship: 'ADMIN' }
  api.inbox = async () => []; api.sent = async () => []; api.messageDirectory = async () => [contact]
  let sends = 0, sendResult = deferred(), allowLeave = false
  api.sendMessage = () => { sends++; return sendResult.promise }
  window.confirm = () => allowLeave
  const router = await mountRoute(MailboxView, '/mail'); await wait(() => button('联系管理员') && !button('写信').disabled)
  button('联系管理员').click(); await wait(() => fixture.querySelector('.compose-modal'))
  await set(fixture.querySelector('.compose-subject input'), 'Unsent subject')
  await set(fixture.querySelector('.compose-body textarea'), 'Unsent body')
  button('取消').click(); await nextTick(); button('写信').click(); await nextTick()
  assert(fixture.querySelector('.compose-subject input').value === 'Unsent subject' && fixture.querySelector('.recipient-chips').textContent.includes('PKB-SYNTHETIC'), 'Closing and reopening preserves content and recipients')
  button('取消').click(); await nextTick(); button('联系管理员').click(); await nextTick()
  assert(!fixture.querySelector('.compose-modal'), 'Cancelling replacement of an existing draft keeps it closed and intact')
  button('写信').click(); await nextTick(); await router.push('/away')
  assert(router.currentRoute.value.path === '/mail', 'Declining navigation preserves an unsent draft')
  const form = fixture.querySelector('.compose-modal')
  form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await nextTick()
  assert(sends === 1 && button('取消').disabled, 'Repeated submit sends once and disables closing while sending')
  fixture.querySelector('.mail-compose-backdrop').click(); form.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); await nextTick()
  assert(fixture.querySelector('.compose-modal'), 'Backdrop clicks and Escape cannot hide a pending send')
  allowLeave = true; await router.push('/away')
  assert(router.currentRoute.value.path === '/mail', 'Even confirmed navigation waits for the current send result')
  sendResult.reject(new ApiClientError('TEMPORARY', 'Send unavailable', 503)); await wait(() => fixture.querySelector('.compose-error'))
  assert(fixture.querySelector('.compose-subject input').value === 'Unsent subject' && fixture.querySelector('.compose-body textarea').value === 'Unsent body', 'Failed sends retain the complete draft')
  sendResult = deferred(); fixture.querySelector('.compose-modal').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await nextTick(); sendResult.resolve()
  await wait(() => !fixture.querySelector('.compose-modal') && !button('写信').disabled)
  button('写信').click(); await nextTick()
  assert(fixture.querySelector('.compose-subject input').value === '' && !fixture.querySelector('.recipient-chips'), 'A successful send clears the draft for the next message')
  assert(document.body.style.overflow === 'hidden', 'Opening the modal locks background scrolling')
  button('取消').click(); await nextTick(); assert(document.body.style.overflow !== 'hidden', 'Closing restores background scrolling')
  await router.push('/away'); assert(router.currentRoute.value.path === '/away', 'Clean forms leave without a draft confirmation')
  app.unmount()

  tokenStorage.set('synthetic-interactions')
  const friend = id => ({ id, userId: id + 100, username: `friend-${id}`, displayName: `Friend ${id}`, identityCode: `PKB-${id}`, status: 'ACCEPTED', incoming: false, blockedByMe: false, available: true, unreadCount: 0 })
  socialApi.contacts = async () => [friend(1), friend(2)]; socialApi.settings = async () => ({ discoverable: true }); socialApi.messages = async () => []
  const contactsRouter = await mountRoute(ContactsView, '/contacts'); await wait(() => button('Friend 1'))
  button('Friend 1').click(); await wait(() => fixture.querySelector('.chat-compose textarea') && !fixture.querySelector('.chat-compose textarea').disabled)
  await set(fixture.querySelector('.chat-compose textarea'), 'Private unfinished message'); allowLeave = false
  button('Friend 2').click(); await nextTick()
  assert(fixture.querySelector('.chat-compose textarea').value === 'Private unfinished message', 'Cancelling a private conversation change preserves its text')
  await contactsRouter.push('/away'); assert(contactsRouter.currentRoute.value.path === '/contacts', 'Private chat protects drafts when navigating away')
  let chatCalls = 0; const chat = deferred(); socialApi.send = () => { chatCalls++; return chat.promise }
  fixture.querySelector('.chat-compose').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); fixture.querySelector('.chat-compose').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await nextTick()
  allowLeave = true; await contactsRouter.push('/away')
  assert(chatCalls === 1 && contactsRouter.currentRoute.value.path === '/contacts', 'A pending private send cannot duplicate or leave its page')
  chat.reject(new Error('Lost connection')); await wait(() => !fixture.querySelector('.chat-compose textarea').disabled)
  assert(fixture.querySelector('.chat-compose textarea').value === 'Private unfinished message', 'Lost private-chat responses keep the draft for idempotent retry')
  await contactsRouter.push('/away'); app.unmount()

  window.fetch = async url => {
    if (!String(url).endsWith('/invitations') && !String(url).endsWith('/lifecycle')) throw new Error('Unexpected group request')
    const data = String(url).endsWith('/lifecycle') ? { archived: false, dissolved: false, acceptRequests: false } : []
    return new Response(JSON.stringify({ success: true, data }), { headers: { 'Content-Type': 'application/json' } })
  }
  authStore.state.user = { id: 301, roleCode: 'RESEARCHER', permissions: ['message:read'] }
  const group = id => ({ id, name: `Synthetic group ${id}`, ownerId: 401, ownerName: 'Owner', currentRole: 'MEMBER', currentPermissions: ['CONTENT_READ', 'CONTENT_WRITE'], members: [] })
  const metadata = id => ({ groupId: id, announcement: '', revision: 0, pinned: false, muted: false, unread: 0, pinnedMessage: null })
  groupApi.overview = async () => ({ groups: [group(1), group(2)], features: [metadata(1), metadata(2)] })
  groupApi.detail = async id => group(id); groupApi.features = async id => metadata(id); groupApi.read = async id => metadata(id); api.groupMessages = async () => []
  const groupsRouter = await mountRoute(GroupsView, '/groups'); await wait(() => fixture.querySelector('#group-draft') && !fixture.querySelector('#group-draft').disabled)
  await set(fixture.querySelector('#group-draft'), 'Unfinished group message'); allowLeave = false
  button('Synthetic group 2').click(); await nextTick()
  assert(fixture.querySelector('.group-header h3').textContent === 'Synthetic group 1' && fixture.querySelector('#group-draft').value === 'Unfinished group message', 'Cancelling group changes keeps the original group and draft')
  await groupsRouter.push('/away'); assert(groupsRouter.currentRoute.value.path === '/groups', 'Group messages also protect unsent drafts on navigation')
  const attachment = fixture.querySelector('.group-compose input[type=file]')
  const pick = async files => { Object.defineProperty(attachment, 'files', { configurable: true, value: files }); attachment.dispatchEvent(new Event('change', { bubbles: true })); await nextTick() }
  const file = new File(['fixture'], 'fixture.txt', { lastModified: 1 })
  await pick([file]); await pick([file]); assert(fixture.querySelectorAll('.group-draft-files li').length === 1, 'Selecting a group attachment again does not duplicate it')
  await pick(Array.from({ length: 6 }, (_, i) => new File(['fixture'], `extra-${i}.txt`)))
  assert(fixture.querySelectorAll('.group-draft-files li').length === 1 && fixture.textContent.includes('已选附件仍保留'), 'Invalid attachment selections preserve existing group files')
  groupApi.detail = async () => { throw new ApiClientError('TEMPORARY', 'Group refresh failed', 503) }
  button('刷新列表').click(); await wait(() => fixture.textContent.includes('Group refresh failed'))
  assert(fixture.querySelector('#group-draft').value === 'Unfinished group message' && fixture.querySelector('.group-header h3').textContent === 'Synthetic group 1', 'A failed refresh keeps the current group draft accessible')
  groupApi.detail = async id => group(id)
  allowLeave = true; button('Synthetic group 2').click(); await wait(() => fixture.querySelector('.group-header h3')?.textContent === 'Synthetic group 2')
  assert(fixture.querySelector('#group-draft').value === '', 'Explicitly confirmed group changes start a separate draft')
  await set(fixture.querySelector('#group-draft'), 'Second-group draft')
  button('刷新列表').click(); await wait(() => fixture.querySelector('#group-draft') && !button('刷新列表').disabled)
  assert(fixture.querySelector('.group-header h3').textContent === 'Synthetic group 2' && fixture.querySelector('#group-draft').value === 'Second-group draft', 'Refreshing the list preserves the selected group and its draft')
  await groupsRouter.push('/away'); app.unmount()

  authStore.clearSession(); let registrations = 0, registration = deferred(); api.register = () => { registrations++; return registration.promise }
  const registerRouter = await mountRoute(RegisterView, '/register')
  await set(fixture.querySelector('[autocomplete=username]'), 'synthetic-user')
  await set(fixture.querySelector('[autocomplete=email]'), 'synthetic@example.test')
  await set(fixture.querySelector('[autocomplete=new-password]'), 'SyntheticPassword123!')
  await set(fixture.querySelectorAll('input[type=password]')[1], 'SyntheticPassword123!')
  const registerForm = fixture.querySelector('form'); registerForm.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); registerForm.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await nextTick()
  assert(registrations === 1, 'Repeated registration submissions cannot create duplicate requests')
  await registerRouter.push('/away'); const priorNotices = notices; registration.resolve({ id: 301 }); await nextTick(); await nextTick()
  assert(registerRouter.currentRoute.value.path === '/away' && notices === priorNotices, 'A late registration cannot redirect or notify after leaving')
  app.unmount()
  let logins = 0; const login = deferred(); authStore.login = () => { logins++; authStore.state.loading = true; return login.promise }
  const loginRouter = await mountRoute(LoginView, '/login')
  await set(fixture.querySelector('[autocomplete=username]'), 'synthetic-user'); await set(fixture.querySelector('[autocomplete=current-password]'), 'SyntheticPassword123!')
  const loginForm = fixture.querySelector('form'); loginForm.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); loginForm.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await nextTick()
  assert(logins === 1, 'Repeated login submissions start one authentication attempt')
  await loginRouter.push('/away'); login.resolve({ id: 301 }); await nextTick(); await nextTick()
  assert(loginRouter.currentRoute.value.path === '/away', 'Late login completion does not override the page chosen by the user')
  app.unmount(); app = null
  tokenStorage.set('synthetic-timeout-session'); authStore.state.loading = false
  const durations = []; let capturedSignal
  // Accelerate only the application deadlines, leaving UI polling timers unchanged.
  globalThis.setTimeout = (callback, duration, ...args) => { if (duration >= 30000) { durations.push(duration); return nativeTimeout(callback, 15, ...args) } return nativeTimeout(callback, duration, ...args) }
  const lateResponse = deferred(); window.fetch = (_, init) => { capturedSignal = init.signal; return lateResponse.promise }
  let failure
  try { await request('/synthetic/stalled', { method: 'POST', body: '{}' }) } catch (error) { failure = error }
  assert(failure instanceof ApiClientError && failure.code === 'REQUEST_TIMEOUT' && failure.message.includes('可能已完成') && capturedSignal.aborted, 'Network deadlines release pending submissions without silently retrying them')
  lateResponse.resolve(new Response(JSON.stringify({ success: false }), { status: 401, headers: { 'Content-Type': 'application/json' } })); await nextTick(); await nextTick()
  assert(tokenStorage.get() === 'synthetic-timeout-session', 'A response arriving after cancellation cannot expire the current session')
  window.fetch = async () => ({ ok: true, blob: () => new Promise(() => {}) })
  try { await fetchBlob('/synthetic/download') } catch (error) { failure = error }
  assert(failure.code === 'REQUEST_TIMEOUT' && durations.at(-1) === 120000, 'Download deadlines also bound stalled response bodies')
  window.fetch = async (_, init) => { capturedSignal = init.signal; return new Promise(() => {}) }
  const cancellation = new AbortController(), cancelReason = new Error('User navigated away')
  const cancelled = request('/synthetic/cancelled', { signal: cancellation.signal }); cancellation.abort(cancelReason)
  try { await cancelled } catch (error) { failure = error }
  assert(failure === cancelReason && capturedSignal.aborted, 'Explicit page cancellation keeps its reason and aborts the request')
  const longRequest = request('/personal-ai/execute', { method: 'POST', body: '{}' })
  try { await longRequest } catch (error) { failure = error }
  assert(failure.code === 'REQUEST_TIMEOUT' && durations.at(-1) === 180000, 'Confirmed AI calls receive the longer execution deadline')
  const upload = request('/synthetic/upload', { method: 'POST', body: new FormData() })
  try { await upload } catch (error) { failure = error }
  assert(failure.code === 'REQUEST_TIMEOUT' && durations.at(-1) === 120000, 'Uploads receive a longer deadline than ordinary requests')
  document.documentElement.dataset.result = 'passed'
  document.getElementById('results').textContent = `PASS (${checks.length} assertions)\n${checks.join('\n')}`
} catch (error) {
  document.documentElement.dataset.result = 'failed'; document.getElementById('results').textContent = error.stack; throw error
} finally {
  app?.unmount(); Object.assign(api, original.api); Object.assign(adminApi, original.admin); Object.assign(workflowApi, original.workflows)
  Object.assign(groupApi, original.groups); Object.assign(socialApi, original.social); authStore.login = original.login; window.fetch = original.fetch
  globalThis.setTimeout = nativeTimeout
  window.confirm = original.confirm; toastStore.success = original.success; toastStore.error = original.error
  if (svgFocus) SVGElement.prototype.focus = svgFocus; else delete SVGElement.prototype.focus
  if (svgBlur) SVGElement.prototype.blur = svgBlur; else delete SVGElement.prototype.blur
  authStore.clearSession()
}
