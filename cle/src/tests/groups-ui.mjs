// All users, messages, and requests in this suite are synthetic.
import { createApp, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import GroupsView from '../views/GroupsView.vue'
import MailboxView from '../views/MailboxView.vue'
import { authStore } from '../stores/auth.ts'
import { tokenStorage } from '../api/client.ts'

const passed = [], fixture = document.getElementById('fixture')
const originalConfirm = window.confirm
window.confirm = () => true
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
const contacts = [{ id: 10, identityCode: 'PKB-00000000000000000000000000000010', displayName: 'Admin', username: 'admin', relationship: 'ADMIN' }, { id: 12, identityCode: 'PKB-0123456789ABCDEF0123456789ABCDEF', displayName: 'Colleague', username: 'colleague', relationship: 'GROUP_MEMBER' }]
const metadataRows = new Map()
const metadata = id => metadataRows.get(id) || { groupId: id, announcement: '', revision: 0, pinned: false, muted: false, unread: 0, pinnedMessage: null }
window.fetch = async (url, init = {}) => {
  const path = String(url); requests.push({ path, method: init.method || 'GET' })
  if(path.endsWith('/invitations')||path.endsWith('/reports'))return envelope([])
  if(path.endsWith('/lifecycle'))return envelope({archived:false,dissolved:false,acceptRequests:false})
  if (path === '/api/v1/workspaces') return envelope(groups)
  if (path === '/api/v1/workspaces/overview') return envelope({ groups, features: groups.map(g => metadata(g.id)) })
  if (/\/workspaces\/\d+$/.test(path)) return envelope(groups.find(g => g.id === Number(path.split('/').at(-1))))
  if (path.endsWith('/features') || path.endsWith('/read')) return envelope(metadata(Number(path.split('/').at(-2))))
  if (path.endsWith('/preferences')) { const id = Number(path.split('/').at(-2)), value = { ...metadata(id), ...JSON.parse(init.body) }; metadataRows.set(id, value); return envelope(value) }
  if (path.endsWith('/announcement') || path.endsWith('/pin')) { const id = Number(path.split('/').at(-2)), input = JSON.parse(init.body); if (input.revision !== metadata(id).revision) return new Response(JSON.stringify({ success: false, error: { code: 'GROUP_NOTICE_CONFLICT', message: '公告已在别处更新，请刷新' } }),{status:409,headers:{'Content-Type':'application/json'}}); const value = { ...metadata(id), revision: input.revision + 1 }; if ('text' in input) value.announcement = input.text; else value.pinnedMessage = input.messageId ? { ...message(input.messageId), body: 'Pinned discussion' } : null; metadataRows.set(id, value); return envelope(value) }
  if (path.includes('/people?')) { const page = Number(new URL(path,'http://synthetic.test').searchParams.get('page')); return envelope({ items: page ? [{ ...contacts[1], id: 13, identityCode: 'PKB-unique-thirteen' }] : contacts, page, hasMore: !page }) }
  if (path.includes('/search?')) return envelope([message('found')])
  if (path.includes('/messages/directory')) { const params = new URL(path, 'http://synthetic.test').searchParams; return envelope(contacts.filter(user => (!params.get('userId') || user.id === Number(params.get('userId'))) && (!params.get('q') || `${user.displayName} ${user.identityCode}`.toLowerCase().includes(params.get('q').toLowerCase())))) }
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
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component }] })
  await router.push('/app/groups'); await router.isReady()
  app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(fixture)
}
try {
  tokenStorage.set('synthetic-group-session')
  authStore.state.user = { id: 2, roleCode: 'RESEARCHER', permissions: ['workspace:manage', 'group:use', 'message:read'] }
  await mount(GroupsView); await wait(() => fixture.querySelector('.group-message'))
  assert(!!button('新建群组'), 'Researchers can create a group')
  assert(!!button('发送到群组'), 'Group admin can discuss in its group')
  assert(!fixture.querySelector('.group-message img') && fixture.textContent.includes('<img src=x'), 'Message HTML renders as text')
  assert(![...fixture.querySelectorAll('.group-add option')].some(node => node.value === 'ADMIN'), 'Group admin cannot grant an admin role')
  assert(!fixture.querySelector('.group-member select'), 'Group admin cannot change owner or admin roles')
  button('置顶会话').click(); await wait(() => button('取消置顶会话'))
  assert(fixture.querySelector('.group-sidebar').textContent.includes('置顶 · '), 'Pinning a conversation updates only the personal group list')
  button('免打扰').click(); await wait(() => button('关闭免打扰'))
  assert(fixture.querySelector('.group-sidebar').textContent.includes('免打扰 · '), 'Mute status remains visible in the group list')
  fixture.querySelector('.group-announcement button').click(); await nextTick()
  const announcement = fixture.querySelector('[aria-label="群公告内容"]'); announcement.value = 'Weekly reading'; announcement.dispatchEvent(new Event('input', { bubbles: true })); await nextTick(); fixture.querySelector('.group-announcement form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})); await wait(() => fixture.querySelector('.group-announcement').textContent.includes('Weekly reading') && !fixture.querySelector('.group-announcement textarea'))
  assert(metadata(1).announcement === 'Weekly reading' && metadata(1).revision === 1, 'Announcement updates use the current revision')
  fixture.querySelector('.group-announcement button').click();await nextTick()
  metadataRows.set(1,{...metadata(1),announcement:'Changed elsewhere',revision:2});window.dispatchEvent(new Event('pkb:live-update'));await new Promise(resolve=>setTimeout(resolve,20));await nextTick()
  fixture.querySelector('.group-announcement form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await wait(()=>fixture.querySelector('[role=alert]')?.textContent.includes('请刷新'))
  assert(metadata(1).announcement==='Changed elsewhere','A live update cannot let an old announcement draft overwrite newer content')
  fixture.querySelector('.group-announcement button').click();await nextTick()
  const memberSearch=fixture.querySelector('#group-person-search'); memberSearch.dispatchEvent(new Event('focus')); await wait(()=>fixture.querySelectorAll('.group-people-menu [role=option]').length===2)
  assert(fixture.querySelector('.group-people-menu').textContent.includes(contacts[1].identityCode),'Invitation dropdown identifies users by unique identity code')
  button('更多用户').click(); await wait(()=>fixture.querySelectorAll('.group-people-menu [role=option]').length===3)
  assert(fixture.querySelector('.group-people-menu').textContent.includes('PKB-unique-thirteen'),'Invitation results can load another page without discarding earlier results')
  button('收起').click(); await nextTick()
  fixture.querySelector('.group-message header button:last-child').click(); await wait(()=>fixture.querySelector('.group-pin'))
  assert(metadata(1).revision === 3 && fixture.querySelector('.group-pin').textContent.includes('Pinned discussion'),'Pinned discussion appears above the thread')
  button('查找消息').click(); await nextTick(); const query=fixture.querySelector('[aria-label="搜索群内消息"]');query.value='content';query.dispatchEvent(new Event('input',{bubbles:true}));await nextTick();fixture.querySelector('.group-search form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await wait(()=>fixture.querySelector('.search-result'))
  assert(!fixture.querySelector('.search-result img')&&fixture.querySelector('.search-result').textContent.includes('<img'),'Message search renders untrusted bodies as text')
  button('关闭搜索').click(); await nextTick()
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
  button('写信').click(); await wait(() => fixture.querySelector('[role=combobox]') === document.activeElement)
  assert(document.body.style.overflow === 'hidden', 'Writing locks background scrolling and focuses recipient search')
  await wait(() => fixture.querySelectorAll('[role=option]').length === 2)
  for (const option of fixture.querySelectorAll('[role=option]')) { option.click(); await nextTick() }
  const searchInput = fixture.querySelector('[role=combobox]'); searchInput.value = 'pkb-0123456789abcdef0123456789abcdef'; searchInput.dispatchEvent(new Event('input', { bubbles: true }));
  await wait(() => fixture.querySelectorAll('[role=option]').length === 1)
  assert(fixture.querySelectorAll('.recipient-chip').length === 2, 'Searching an identity code keeps selected recipient chips visible')
  fixture.querySelector('[aria-label^="移除收件人 Admin"]').click(); await nextTick()
  assert(fixture.querySelectorAll('.recipient-chip').length === 1 && fixture.querySelector('.recipient-chip').textContent.includes('Colleague'), 'Removing a chip removes only its recipient')
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
  button('回复这封信').click(); await wait(() => fixture.querySelector('.recipient-chip'))
  assert(fixture.querySelector('.recipient-chip').textContent.includes('Admin'), 'Reply selects the original sender')
  assert(fixture.querySelector('.compose-modal input[maxlength="180"]').value === '回复：Question', 'Reply prepares the subject')
  fixture.querySelector('[aria-label="展开收件人"]').click(); await nextTick()
  assert(fixture.querySelectorAll('[role=option]').length === 1 && fixture.querySelector('[role=option]').textContent.includes('Admin'), 'Reply only offers the original sender')
  fixture.querySelector('[aria-label^="移除收件人 Admin"]').click(); await nextTick()
  assert(!fixture.querySelector('[role=option]').disabled, 'Removing the reply recipient still allows selecting the original sender again')
  fixture.querySelector('[role=option]').click(); await nextTick()

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
} finally { app?.unmount(); window.confirm = originalConfirm }
