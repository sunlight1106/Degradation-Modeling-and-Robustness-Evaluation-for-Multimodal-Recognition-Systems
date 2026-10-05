// All users, messages, and requests in this suite are synthetic.
import { createApp, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import GroupsView from '../views/GroupsView.vue'
import MailboxView from '../views/MailboxView.vue'
import { authStore } from '../stores/auth.ts'
import { tokenStorage } from '../api/client.ts'

const passed = [], fixture = document.getElementById('fixture')
const assert = (ok, label) => { if (!ok) throw new Error(label); passed.push(label) }
const wait = async predicate => { for (let i = 0; i < 100; i++) { await new Promise(resolve => setTimeout(resolve, 5)); await nextTick(); if (predicate()) return } throw new Error('UI wait timed out') }
const button = label => [...fixture.querySelectorAll('button')].find(node => node.textContent.includes(label))
const envelope = data => new Response(JSON.stringify({ success: true, data }), { headers: { 'Content-Type': 'application/json' } })
const rights = ['CONTENT_READ', 'CONTENT_WRITE', 'MEMBERS_READ', 'MEMBERS_WRITE', 'SETTINGS_WRITE']
const groups = [
  { id: 1, name: 'Group Alpha', ownerId: 10, ownerName: 'Owner', currentRole: 'ADMIN', currentPermissions: rights, members: [{ id: 1, userId: 2, displayName: 'Me', username: 'me', role: 'ADMIN' }, { id: 2, userId: 10, displayName: 'Owner', username: 'owner', role: 'OWNER' }] },
  { id: 2, name: 'Group Beta', ownerId: 11, ownerName: 'Other owner', currentRole: 'VIEWER', currentPermissions: ['CONTENT_READ'], members: [{ id: 3, userId: 2, displayName: 'Me', username: 'me', role: 'VIEWER' }] },
]
const message = (id, workspaceId = 1) => ({ id, workspaceId, senderId: 10, senderName: 'Owner', subject: 'Question', body: '<img src=x onerror=alert(1)> Synthetic content', attachments: [], recipients: [], read: false, createdAt: '2026-10-05T10:00:00Z', replyToId: null })
let app, deferredSend, deferredList, delayAlpha = false
const sends = [], requests = []
const contacts = [{ id: 10, displayName: 'Admin', username: 'admin', relationship: 'ADMIN' }, { id: 12, identityCode: 'PKB-0123456789ABCDEF0123456789ABCDEF', displayName: 'Colleague', username: 'colleague', relationship: 'GROUP_MEMBER' }]
window.fetch = async (url, init = {}) => {
  const path = String(url); requests.push({ path, method: init.method || 'GET' })
  if (path === '/api/v1/workspaces') return envelope(groups)
  if (path.endsWith('/directory')) return envelope(contacts)
  if (path.includes('/messages/groups/')) {
    if (init.method === 'POST') { sends.push({ path, body: init.body }); return new Promise(resolve => { deferredSend = () => { deferredSend = null; resolve(envelope(message('sent'))) } }) }
    if (path.includes('/groups/1') && delayAlpha) return new Promise(resolve => { deferredList = () => resolve(envelope([message('late-alpha')])) })
    return envelope([message(path.includes('/groups/1') ? 'alpha' : 'beta', path.includes('/groups/1') ? 1 : 2)])
  }
  if (path.endsWith('/messages/inbox')) return envelope([message('private', null)])
  if (path.endsWith('/messages/sent')) return envelope([])
  if (path.endsWith('/messages/private')) return envelope(message('private', null))
  if (path.endsWith('/messages') && init.method === 'POST') {
    sends.push({ path, body: init.body }); return new Promise(resolve => { deferredSend = () => { deferredSend = null; resolve(envelope(message('reply', null))) } })
  }
  throw new Error(`Unexpected request: ${path}`)
}
async function mount(component) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/app/groups'); await router.isReady()
  app = createApp(component); app.use(router); app.mount(fixture)
}
try {
  tokenStorage.set('synthetic-group-session')
  authStore.state.user = { id: 2, roleCode: 'RESEARCHER', permissions: ['workspace:manage'] }
  await mount(GroupsView); await wait(() => fixture.querySelector('.group-message'))
  assert(!!button('新建群组'), 'Researchers can create a group')
  assert(!!button('发送到群组'), 'Group admin can discuss in its group')
  assert(!fixture.querySelector('.group-message img') && fixture.textContent.includes('<img src=x'), 'Message HTML renders as text')
  assert(![...fixture.querySelectorAll('.group-add option')].some(node => node.value === 'ADMIN'), 'Group admin cannot grant an admin role')
  assert(!fixture.querySelector('.group-member select'), 'Group admin cannot change owner or admin roles')
  button('回复').click(); await nextTick()
  assert(fixture.querySelector('.group-reply').textContent.includes('Owner'), 'Reply indicates the original sender')
  const draft = fixture.querySelector('#group-draft'); draft.value = 'Synthetic reply'; draft.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  fixture.querySelector('.group-compose').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  fixture.querySelector('.group-compose').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await wait(() => deferredSend)
  assert(sends.length === 1 && sends[0].body.get('replyToId') === 'alpha', 'Duplicate submission is blocked and reply identity is preserved')
  assert(button('正在处理').disabled && button('Group Beta').disabled, 'Sending locks submit and group changes')
  deferredSend(); await wait(() => fixture.querySelector('#group-draft').value === '' && button('发送到群组').disabled)
  assert(!fixture.querySelector('.group-reply'), 'Successful send clears the draft reply')
  button('Group Beta').click(); await wait(() => fixture.querySelector('.group-header h3')?.textContent === 'Group Beta' && !fixture.querySelector('[aria-busy="true"]'))
  assert(!button('发送到群组') && !!fixture.querySelector('.group-readonly'), 'Read-only members have no composer')
  assert(!fixture.querySelector('.group-add'), 'Read-only members cannot invite users')
  delayAlpha = true; button('Group Alpha').click(); await wait(() => deferredList)
  button('Group Beta').click(); await wait(() => fixture.querySelector('.group-header h3')?.textContent === 'Group Beta')
  deferredList(); await new Promise(resolve => setTimeout(resolve, 20)); await nextTick()
  assert(fixture.querySelector('.group-header h3').textContent === 'Group Beta' && fixture.querySelectorAll('.group-message').length === 1, 'Late responses cannot replace another group')
  authStore.state.user = null; await nextTick()
  assert(!fixture.querySelector('.group-message') && !fixture.querySelector('.group-header'), 'Account changes clear group content and selection')
  app.unmount(); fixture.innerHTML = ''

  authStore.state.user = { id: 2, roleCode: 'BASIC', permissions: [] }
  await mount(MailboxView); await wait(() => fixture.querySelector('.mail-row'))
  assert(!!button('写信') && !!button('联系管理员'), 'Basic users without message permissions can contact an administrator')
  button('写信').click(); await wait(() => fixture.querySelector('.compose-search input') === document.activeElement)
  assert(document.body.style.overflow === 'hidden', 'Writing locks background scrolling and focuses recipient search')
  const checkboxes = [...fixture.querySelectorAll('.mail-contacts input')]
  for (const checkbox of checkboxes) { checkbox.checked = true; checkbox.dispatchEvent(new Event('change', { bubbles: true })); await nextTick() }
  const searchInput = fixture.querySelector('.compose-search input'); searchInput.value = 'pkb-0123456789abcdef0123456789abcdef'; searchInput.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  assert(fixture.querySelectorAll('.compose-contact').length === 1 && fixture.querySelectorAll('.compose-recipient-chip').length === 2, 'Searching an identity code matches the contact and keeps selected recipient chips visible')
  fixture.querySelector('[aria-label="移除收件人 Admin"]').click(); await nextTick()
  assert(fixture.querySelectorAll('.compose-recipient-chip').length === 1 && fixture.querySelector('.compose-recipient-chip').textContent.includes('Colleague'), 'Removing a chip removes only its recipient')
  const fileInput = fixture.querySelector('input[type="file"]')
  const pick = async files => { Object.defineProperty(fileInput, 'files', { configurable: true, value: files }); fileInput.dispatchEvent(new Event('change', { bubbles: true })); await nextTick() }
  const firstFile = new File(['synthetic'], 'notes.txt', { type: 'text/plain', lastModified: 1 })
  await pick([firstFile]); await pick([firstFile, new File(['synthetic'], 'report.txt', { type: 'text/plain', lastModified: 2 })])
  assert(fixture.querySelectorAll('.compose-files li').length === 2, 'Adding attachments retains prior files without duplicates')
  await pick(Array.from({length: 5}, (_, i) => new File(['data'], `extra${i}.txt`)))
  assert(fixture.querySelectorAll('.compose-files li').length === 2 && fixture.querySelector('.compose-error'), 'Invalid attachment batches preserve the existing attachments')
  fixture.querySelector('[aria-label="移除附件 notes.txt"]').click(); await nextTick()
  assert(fixture.querySelectorAll('.compose-files li').length === 1, 'Attachments can be removed individually')
  fixture.querySelector('[aria-label="关闭写信"]').click(); await nextTick()
  assert(document.body.style.overflow !== 'hidden', 'Closing the composer restores page scrolling')
  fixture.querySelector('.mail-row').click(); await wait(() => button('回复这封信'))
  button('回复这封信').click(); await nextTick()
  assert(fixture.querySelector('.mail-contacts input').checked, 'Reply selects the original sender')
  assert(fixture.querySelector('.compose-modal input[maxlength="180"]').value === '回复：Question', 'Reply prepares the subject')
  assert(fixture.querySelector('.mail-contacts input[value="12"]').disabled, 'Reply cannot add another recipient')
  fixture.querySelector('[aria-label="移除收件人 Admin"]').click(); await nextTick()
  const replyContact = fixture.querySelector('.mail-contacts input[value="10"]')
  assert(!replyContact.disabled, 'Removing the reply recipient still allows selecting the original sender again')
  replyContact.checked = true; replyContact.dispatchEvent(new Event('change', {bubbles:true})); await nextTick()

  const body = fixture.querySelector('.compose-modal textarea'); body.value = 'Synthetic private reply'; body.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  fixture.querySelector('.compose-modal').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await wait(() => deferredSend)
  assert(sends.at(-1).body.get('replyToId') === 'private' && sends.at(-1).body.get('recipientIds') === '10', 'Private reply sends only to its selected participant')
  authStore.state.user = null; await nextTick(); deferredSend(); await new Promise(resolve => setTimeout(resolve, 20)); await nextTick()
  assert(!fixture.querySelector('.compose-modal') && !fixture.querySelector('.mail-row'), 'Late sends cannot restore private state after account changes')
  app.unmount(); app = null
  document.documentElement.dataset.result = 'passed'
  document.getElementById('results').textContent = `PASS ${passed.length}\n${passed.join('\n')}`
} catch (error) {
  document.documentElement.dataset.result = 'failed'
  document.getElementById('results').textContent = `FAIL after ${passed.length}: ${error.stack}`
} finally { app?.unmount() }
