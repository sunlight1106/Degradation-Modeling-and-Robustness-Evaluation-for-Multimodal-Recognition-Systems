// Mounted Logs recovery flow. Every HTTP response and task is synthetic.
import { createApp, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import LogsView from '../views/LogsView.vue'
import { authStore } from '../stores/auth.ts'
import { tokenStorage } from '../api/client.ts'

const passed = [], fixture = document.getElementById('fixture')
const assert = (ok, message) => { if (!ok) throw new Error(message); passed.push(message) }
const wait = async (until = () => true) => {
  for (let i = 0; i < 100; i++) { await new Promise(resolve => setTimeout(resolve, 5)); await nextTick(); if (until()) return }
  throw new Error('Timed out waiting for queue recovery UI')
}
const button = label => [...fixture.querySelectorAll('button')].find(node => node.textContent.includes(label))
const envelope = (data, status = 200) => new Response(JSON.stringify(status < 400 ? { success: true, data } : { success: false, error: data }), { status, headers: { 'Content-Type': 'application/json' } })
const task = (id, status, owner = 1) => ({ id, traceId: `${id}-trace-000`, status, requestedById: owner, requestedBy: `Owner ${owner}`,
  taskType: 'RECEIPT', inputFile: { originalName: `${id}.png` }, model: { name: 'Synthetic DEMO' }, createdAt: '2026-10-02T10:00:00Z' })
let items = [task('own', 'PENDING'), task('other', 'PENDING', 2), task('done', 'COMPLETED'), task('failed', 'FAILED'), task('running', 'RUNNING')]
const recoveries = [], confirms = []
let listCalls = 0, pendingRecovery, confirmAnswer = true, app
const priorFetch = window.fetch, priorConfirm = window.confirm
window.confirm = text => { confirms.push(text); return confirmAnswer }
window.fetch = async (url, init = {}) => {
  const path = String(url)
  if (path.endsWith('/inference/tasks')) { listCalls++; return envelope(items) }
  if (path.endsWith('/recover')) {
    recoveries.push({ path, method: init.method })
    return new Promise(resolve => { pendingRecovery = (data, status = 200) => { pendingRecovery = null; resolve(envelope(data, status)) } })
  }
  throw new Error(`Unexpected request: ${path}`)
}
const open = async id => {
  const row = [...fixture.querySelectorAll('.log-row')].find(node => node.textContent.includes(`${id}.png`))
  row.click(); await nextTick()
}
async function mount() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/app/logs'); await router.isReady()
  app = createApp(LogsView); app.use(router); app.mount(fixture)
  await wait(() => fixture.querySelectorAll('.log-row').length === items.length)
}
try {
  tokenStorage.set('synthetic-queue-session')
  authStore.state.user = { id: 1, permissions: ['experiment:run', 'experiment:read'] }
  await mount()
  assert(listCalls === 1, 'Initial logs use one read request')
  await open('other'); assert(!button('重新入队'), 'Another user’s pending task has no recovery action')
  await open('done'); assert(!button('重新入队'), 'Completed tasks have no recovery action')
  await open('failed'); assert(!button('重新入队'), 'Failed tasks have no recovery action')
  await open('running'); assert(!button('重新入队'), 'RUNNING tasks have no recovery action')
  await open('own'); assert(!!button('重新入队'), 'Owner sees a pending DEMO recovery action')
  confirmAnswer = false; button('重新入队').click(); await nextTick()
  assert(recoveries.length === 0, 'Cancelling confirmation sends no recovery request')
  assert(confirms[0].includes('不调用个人 AI') && confirms[0].includes('trace_id'), 'Confirmation explains synthetic-only work and original task identity')
  confirmAnswer = true; button('重新入队').click(); button('重新入队').click(); await wait(() => pendingRecovery)
  assert(recoveries.length === 1 && recoveries[0].method === 'POST' && recoveries[0].path.endsWith('/own/recover'), 'Repeated clicks send one POST for the same task')
  assert(button('正在恢复').disabled && button('刷新状态').disabled, 'Recovery and refresh are disabled while the request is pending')
  pendingRecovery(task('own', 'PENDING')); await wait(() => button('重新入队') && !button('重新入队').disabled)
  assert(fixture.textContent.includes('已重新入队') && fixture.textContent.includes('仍显示等待中'), 'Successful enqueue is honestly distinguished from completed work')
  assert(fixture.querySelectorAll('.log-row').length === 5, 'Recovery updates rather than duplicates the task row')
  button('运行中').click(); await nextTick()
  assert(fixture.querySelectorAll('.log-row').length === 3, 'Running filter includes both PENDING and RUNNING tasks counted by its summary')
  button('全部调用').click(); await nextTick()
  button('重新入队').click(); await wait(() => pendingRecovery)
  pendingRecovery({ code: 'INFERENCE_QUEUE_UNAVAILABLE', message: '队列暂时不可用，请先刷新状态' }, 503)
  await wait(() => button('重新入队') && !button('重新入队').disabled)
  assert(fixture.textContent.includes('队列暂时不可用'), 'Queue failure is shown without claiming success or deleting the task')
  items = items.map(item => item.id === 'own' ? task('own', 'COMPLETED') : item)
  button('刷新状态').click(); await wait(() => listCalls === 2 && fixture.querySelectorAll('.log-row').length === 5)
  assert(!button('重新入队'), 'Refresh removes recovery after a worker completes the task')
  app.unmount(); app = null
  items = [task('own', 'PENDING')]
  authStore.state.user = { id: 1, permissions: ['experiment:read'] }; await mount(); await open('own')
  assert(!button('重新入队'), 'Owner lacking run permission cannot recover from UI')
  app.unmount(); app = null
  authStore.state.user = { id: 1, permissions: ['experiment:run', 'experiment:read'] }; await mount(); await open('own')
  button('重新入队').click(); await wait(() => pendingRecovery)
  const resolveOld = pendingRecovery
  items = [task('new-account', 'PENDING', 2)]
  tokenStorage.set('synthetic-other-session'); authStore.state.user = { id: 2, permissions: ['experiment:run', 'experiment:read'] }
  await wait(() => fixture.textContent.includes('new-account.png'))
  resolveOld(task('own', 'COMPLETED')); await nextTick(); await wait()
  assert(!fixture.textContent.includes('own.png') && !fixture.textContent.includes('DEMO 实验已完成'), 'A late recovery response cannot leak into a different account')
  await open('new-account'); button('重新入队').click(); await wait(() => pendingRecovery)
  const resolveUnmounted = pendingRecovery
  app.unmount(); app = null; resolveUnmounted(task('new-account', 'COMPLETED')); await nextTick(); await wait()
  assert(fixture.textContent === '', 'Navigation away ignores late recovery completion')
  document.documentElement.dataset.result = 'passed'
  document.getElementById('results').textContent = `PASS ${passed.length} assertions\n${passed.join('\n')}`
} catch (error) {
  document.documentElement.dataset.result = 'failed'
  document.getElementById('results').textContent = `FAIL after ${passed.length} assertions\n${error.stack || error}`
} finally {
  app?.unmount(); window.fetch = priorFetch; window.confirm = priorConfirm; tokenStorage.clear(); authStore.state.user = null
}
