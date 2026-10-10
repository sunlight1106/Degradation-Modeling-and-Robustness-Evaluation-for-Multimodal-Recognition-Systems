// Synthetic sessions only; no real account names, credentials or requests.
import { createApp, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import AppShell from '../components/AppShell.vue'
import AccountAccessView from '../views/AccountAccessView.vue'
import NoteEditorView from '../views/NoteEditorView.vue'
import LoginView from '../views/LoginView.vue'
import { authStore } from '../stores/auth.ts'
import { themeStore } from '../stores/theme.ts'
import { toastStore } from '../stores/toast.ts'
import { rememberedAccounts } from '../stores/rememberedAccounts.ts'
import { tokenStorage } from '../api/client.ts'
globalThis.requestAnimationFrame = window.requestAnimationFrame.bind(window)
globalThis.cancelAnimationFrame = window.cancelAnimationFrame.bind(window)
const fixture = document.getElementById('fixture'), passed = []
const assert = (ok, label) => { if (!ok) throw new Error(label); passed.push(label) }
const wait = async predicate => { for (let i = 0; i < 200; i++) { await new Promise(resolve => setTimeout(resolve, 5)); await nextTick(); if (predicate()) return } throw new Error('UI wait timed out') }
const envelope = data => new Response(JSON.stringify({ success: true, data }), { headers: { 'Content-Type': 'application/json' } })
const button = text => [...fixture.querySelectorAll('button')].find(n => n.textContent.includes(text))
const link = href => fixture.querySelector(`a[href="${href}"]`)
async function set(node, value) { node.value = value; node.dispatchEvent(new Event('input', { bubbles: true })); await nextTick() }
const admin = { id: 1, username: 'synthetic-admin', displayName: 'Synthetic Admin', roleName: '管理员', roleCode: 'ADMIN', permissions: ['dashboard:read', 'note:read', 'note:write', 'user:read', 'role:read', 'contacts:use'] }
const member = { ...admin, id: 2, username: 'synthetic-member', displayName: 'Synthetic Member', roleName: '成员', roleCode: 'VIEWER', permissions: ['dashboard:read'] }
const note = { id: 'one', title: 'Private A title', body: 'Private A content', tags: [], library: '计算机学习', contentFormat: 'MARKDOWN', status: { code: 'DRAFT', label: '草稿' }, references: [], shareCount: 0, revision: 0 }
let app, resolveSwitch, resolveLogout, switchCalls = 0, logoutCalls = 0, rejectTarget = true, allowLeave = false
window.confirm = () => allowLeave
window.fetch = async (url, init = {}) => {
  const path = String(url)
  if (path.endsWith('/notes/one')) return envelope(note)
  if (path.endsWith('/notes')) return envelope([note])
  if (path.includes('/personal-ai/') || path.endsWith('/notes/one/shares') || path.endsWith('/notes/sources/experiments')) return envelope([])
  if (path.endsWith('/account/switch')) {
    switchCalls++
    assert(init.headers.get('Authorization') === 'Bearer source-session', 'Switch authenticates the existing source session')
    if (rejectTarget) return new Response(JSON.stringify({ success: false, error: { code: 'ACCOUNT_SWITCH_FAILED', message: '目标账号或密码不正确，当前登录未改变。' } }), { status: 403, headers: { 'Content-Type': 'application/json' } })
    return new Promise(resolve => { resolveSwitch = () => resolve(envelope({ token: 'target-session', user: member })) })
  }
  if (path.endsWith('/account/logout')) { logoutCalls++; return new Promise(resolve => { resolveLogout = fail => resolve(fail ? new Response(JSON.stringify({ success: false, error: { code: 'TEMPORARY', message: 'Synthetic failure' } }), { status: 503, headers: { 'Content-Type': 'application/json' } }) : envelope(null)) }) }
  throw new Error('Unexpected request: ' + path)
}
try {
  rememberedAccounts.clear()
  for (let i = 0; i < 6; i++) rememberedAccounts.remember({ username: `synthetic-${i}`, displayName: `Name ${i}`, password: 'not-storable', token: 'not-storable' })
  const stored = JSON.parse(localStorage.getItem('personal_platform_account_names'))
  assert(stored.length === 5 && stored.every(item => Object.keys(item).sort().join(',') === 'displayName,username'), 'Remembered accounts are bounded to five names and exclude credentials')
  rememberedAccounts.clear(); rememberedAccounts.remember(member)
  tokenStorage.set('source-session'); authStore.state.user = admin; themeStore.useAccount(admin.id); themeStore.setPreference('dark')
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/app', component: AppShell, children: [
      { path: 'home', component: { render: () => h('p', 'Account home') } },
      { path: 'notes/:id/edit', name: 'note-edit', component: NoteEditorView },
      { path: 'notes', name: 'notes', component: { render: () => h('p', 'Notes') } },
      { path: 'settings', component: { render: () => h('p', 'Settings') } },
    ] },
    { path: '/account/switch', name: 'account-switch', component: AccountAccessView },
    { path: '/account/logout', name: 'account-logout', component: AccountAccessView },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/:pathMatch(.*)*', component: { render: () => h('div') } },
  ] })
  await router.push('/app/notes/one/edit'); await router.isReady(); app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(fixture)
  await wait(() => fixture.querySelector('.note-title-input')?.value === note.title)
  assert(fixture.querySelector('.workspace-account-identity').textContent.includes('@synthetic-admin') && fixture.querySelector('.account-role').textContent === '管理员', 'Account menu displays the exact signed-in identity and role')
  assert(!!link('/app/settings?section=security') && !!link('/app/settings?section=privacy') && !!link('/app/contacts'), 'Account, session, privacy and contact entries are reachable from the directory')
  const menu = fixture.querySelector('details.workspace-directory'); menu.open = true; document.body.dispatchEvent(new Event('pointerdown', { bubbles: true })); await nextTick()
  assert(!menu.open, 'Clicking outside dismisses the account menu')
  await set(fixture.querySelector('.note-title-input'), 'Unsaved A draft')
  link('/account/switch').click(); await nextTick(); await new Promise(resolve => setTimeout(resolve, 15))
  assert(router.currentRoute.value.path.includes('/notes/') && switchCalls === 0, 'Switch navigation respects the note unsaved-changes guard before any revocation')
  link('/account/logout').click(); await nextTick(); await new Promise(resolve => setTimeout(resolve, 15))
  assert(logoutCalls === 0 && authStore.state.user.id === 1, 'Logout also respects the unsaved-changes guard')
  allowLeave = true; link('/account/switch').click(); await wait(() => fixture.querySelector('.account-access-panel'))
  button('Synthetic Member').click(); await nextTick()
  assert(fixture.querySelector('input[autocomplete=username]').value === member.username && !fixture.querySelector('input[autocomplete=current-password]').value, 'Choosing a remembered name fills only the username')
  assert(tokenStorage.get() === 'source-session' && !fixture.textContent.includes('Private A content'), 'The old editor is unmounted before switching and selecting a name does not authenticate')
  await set(fixture.querySelector('input[autocomplete=current-password]'), 'Wrong123!')
  fixture.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await wait(() => fixture.querySelector('[role=alert]'))
  assert(authStore.state.user.id === 1 && tokenStorage.get() === 'source-session' && !fixture.querySelector('input[autocomplete=current-password]').value, 'Wrong target credentials preserve the current account and clear password input')
  rejectTarget = false; await set(fixture.querySelector('input[autocomplete=current-password]'), 'SyntheticPassword123!')
  toastStore.info('Private A transient message')
  fixture.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await wait(() => resolveSwitch)
  const calls = switchCalls; fixture.querySelector('form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await nextTick()
  assert(switchCalls === calls && button('正在验证').disabled, 'Repeated submission is locked during switching')
  resolveSwitch(); await wait(() => router.currentRoute.value.path === '/app/home' && fixture.querySelector('.workspace-account-identity'))
  assert(authStore.state.user.id === 2 && tokenStorage.get() === 'target-session' && !link('/app/users'), 'Successful switch applies the target identity and removes previous admin navigation')
  assert(themeStore.state.preference === 'system' && toastStore.state.items.length === 0, 'Previous account appearance and private toasts do not bleed into the new account')
  link('/account/logout').click(); await wait(() => resolveLogout); resolveLogout(true); await wait(() => button('重试退出'))
  assert(authStore.state.user.id === 2 && tokenStorage.get() === 'target-session', 'Failed server logout preserves authentication and offers retry')
  resolveLogout = null; button('重试退出').click(); await wait(() => resolveLogout); resolveLogout(false)
  await wait(() => router.currentRoute.value.path === '/login')
  assert(!tokenStorage.get() && !authStore.state.user && fixture.textContent.includes('当前会话已退出'), 'Confirmed logout clears authentication and returns to the account selection login page')
  button('移除').click(); await nextTick()
  assert(!localStorage.getItem('personal_platform_account_names'), 'Remembered names can be removed independently of server accounts')
  document.documentElement.dataset.result = 'passed'; document.getElementById('results').textContent = `PASS (${passed.length} assertions)`
} catch (error) { document.documentElement.dataset.result = 'failed'; document.getElementById('results').textContent = error.stack || String(error) }
finally { app?.unmount(); rememberedAccounts.clear(); tokenStorage.clear(); authStore.state.user = null; toastStore.clear() }
