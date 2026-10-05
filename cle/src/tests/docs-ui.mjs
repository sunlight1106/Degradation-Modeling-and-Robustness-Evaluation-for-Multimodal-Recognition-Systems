// Mounted help-page regression suite. No real credentials, backend, or provider calls.
import { createApp, nextTick } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import DocsView from '../views/DocsView.vue'
import OverviewView from '../views/OverviewView.vue'
import { authStore } from '../stores/auth.ts'

const passed = []
const fixture = document.getElementById('fixture')
const assert = (value, message) => { if (!value) throw new Error(message); passed.push(message) }
const settle = async () => { await nextTick(); await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() }
const button = text => Array.from(fixture.querySelectorAll('button')).find(el => el.textContent.includes(text))
const click = async text => { const el = button(text); if (!el) throw new Error(`Missing button ${text}`); el.click(); await settle() }
const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
let copiedText, httpCalls = 0, scrollTarget
Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async text => { copiedText = text } } })
window.fetch = async () => { httpCalls++; throw new Error('Help page must not call APIs for an anonymous reader') }
window.HTMLElement.prototype.scrollIntoView = function () { scrollTarget = this.id }
let app
try {
  app = createApp(DocsView).use(router); app.mount(fixture); await settle()
  const content = fixture.textContent
  assert(fixture.querySelectorAll('h1').length === 1 && fixture.querySelector('article[aria-label]'), 'Guide has one title and a named article')
  assert(Array.from(fixture.querySelectorAll('nav')).every(el => el.getAttribute('aria-label')), 'Every navigation region has an accessible name')
  assert(Array.from(fixture.querySelectorAll('button')).every(el => (el.getAttribute('aria-label') || el.textContent).trim()), 'Every help button has an accessible name')
  const anchors = Array.from(fixture.querySelectorAll('a[href^="#"]'))
  assert(anchors.length >= 26 && anchors.every(el => fixture.querySelector(el.getAttribute('href'))), 'All sidebar, page and legacy section anchors resolve')
  assert(new Set(Array.from(fixture.querySelectorAll('[id]')).map(el => el.id)).size === fixture.querySelectorAll('[id]').length, 'Help section IDs are unique')
  const hrefs = new Set(Array.from(fixture.querySelectorAll('a')).map(el => el.getAttribute('href')))
  for (const href of ['/register', '/login', '/app/notes/new', '/app/notes', '/app/upload', '/app/knowledge', '/app/vocabulary', '/app/settings?section=ai', '/app/settings?section=profile', '/app/settings?section=security', '/app/settings?section=privacy', '/app/settings?section=appearance', '/app/settings?section=usage', '/swagger-ui.html']) {
    assert(hrefs.has(href), `First-run navigation links to ${href}`)
  }
  for (const required of ['http://localhost:4173/api/v1', 'PERSONAL_AI_REMOTE_ENABLED=false', 'AES-256-GCM', '至少 32 UTF-8 字节', '9 类适配器', '模拟上游和合成数据', '真实视频调用暂未开放', 'DeepSeek 视觉兼容性尚未验证', '5 MiB（5,242,880 字节）', 'LOCAL_RULES', '不会静默截断', '24,000 字符', '最多选 20 份', '最近最多 100 次', '未提供的 Token 数记为未知', '17 本内置词书', '14,894 个不同单词', '累计答对 4 次', '下一个本地日历日期', '4–500 词', '最多 2 MiB', 'MODEL_MODE=demo']) {
    assert(content.includes(required), `Rendered guide states ${required}`)
  }
  for (const stale of ['localhost:8080', 'MODEL_MODE=live', 'DEEPSEEK_API_KEYS', 'KIMI_API_KEYS', 'QWEN_API_KEYS', 'QWEN_VIDEO_MODEL', 'Kimi 使用 Files API', '通过 Files API 上传视频', '千问视频会路由', '至少配置 DeepSeek', '等待 180 秒', '未配置时走本地规则引擎', '已经实现真实推理', '模型 API 预算与余额']) {
    assert(!content.includes(stale), `Removed misleading legacy guidance: ${stale}`)
  }
  const visibleCode = () => fixture.querySelector('[aria-label="个人 AI 请求示例"] pre code').textContent
  const assertCopiedJson = async expected => {
    const shown = visibleCode()
    assert(JSON.stringify(JSON.parse(shown)) === JSON.stringify(expected), 'Rendered request body is exact, parseable JSON')
    await click('复制 JSON')
    assert(copiedText === shown && button('已复制'), 'Copy button copies the exact rendered JSON without shell escaping')
  }
  await assertCopiedJson({provider:'OPENAI',action:'summarize',title:'无敏感信息的练习笔记',body:'这是一段用于检查预览的示例文本。尚未验证任何实验结论。',selectedTaskIds:[]})
  await click('图片预览 JSON')
  await assertCopiedJson({provider:'OPENAI',fileId:'<自己的已扫描图片 ID>',taskType:'RECEIPT'})
  assert(fixture.querySelector('[aria-label="个人 AI 请求示例"]').textContent.includes('POST /api/v1/personal-ai/recognition/preview'), 'Image example displays the actual preview endpoint')
  await click('确认执行 JSON')
  await assertCopiedJson({previewToken:'<本次预览返回的一次性令牌>',confirmed:true})
  assert(fixture.querySelector('[aria-label="个人 AI 请求示例"]').textContent.includes('POST /api/v1/personal-ai/recognition/execute'), 'Execute example distinguishes image and text endpoints and tokens')
  assert(fixture.querySelectorAll('[aria-label="选择请求示例"] [aria-pressed="true"]').length === 1, 'Current JSON example is exposed as pressed')
  assert(!visibleCode().includes('apiKey') && !copiedText.includes('Authorization'), 'Copyable examples contain no credentials or login tokens')

  const search = fixture.querySelector('input[aria-label="搜索使用文档"]')
  const shortcut = new window.KeyboardEvent('keydown', { key: 'k', ctrlKey: true, cancelable: true })
  window.dispatchEvent(shortcut)
  assert(document.activeElement === search && shortcut.defaultPrevented, 'Ctrl+K focuses the labeled document search')
  search.value = 'BYOK'; search.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  assert(fixture.querySelector('[aria-label="文档搜索结果"] a')?.getAttribute('href') === '#model-contract', 'Search finds configuration by the BYOK keyword')
  search.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); await nextTick()
  assert(scrollTarget === 'model-contract' && search.value === '', 'Enter navigates to the matching section and clears search')
  search.value = 'no-such-help-topic'; search.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  assert(fixture.querySelector('[role="status"]')?.textContent.includes('没有匹配章节'), 'Empty search result is announced accessibly')
  assert(httpCalls === 0, 'Reading help, searching and copying examples makes no API or provider calls')
  app.unmount()
  const afterUnmount = new window.KeyboardEvent('keydown', { key: 'k', ctrlKey: true, cancelable: true })
  window.dispatchEvent(afterUnmount)
  assert(!afterUnmount.defaultPrevented, 'Leaving the help page removes its keyboard shortcut')

  const ensureUser = authStore.ensureUser
  const addEventListener = window.addEventListener
  let finishUser, lateListeners = 0
  authStore.ensureUser = () => new Promise(resolve => { finishUser = resolve })
  window.addEventListener = function (type, ...args) { if (type === 'keydown' || type === 'scroll') lateListeners++; return addEventListener.call(this, type, ...args) }
  try {
    app = createApp(DocsView).use(router); app.mount(fixture); app.unmount(); finishUser(null); await settle()
    assert(lateListeners === 0 && fixture.children.length === 0, 'Late authentication resolution after navigation cannot attach listeners to a destroyed guide')
  } finally { authStore.ensureUser = ensureUser; window.addEventListener = addEventListener }
  const envelope = data => new Response(JSON.stringify({ success: true, data }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  let providerValue = null
  window.fetch = async url => {
    if (String(url).endsWith('/dashboard/summary')) return envelope({ totalTasks: 0, successRate: 0, completedTasks: 0, averageConfidenceLift: 0, failedTasks: 0, recentTasks: [] })
    if (String(url).endsWith('/billing/summary')) return envelope({ wallet: { balanceCny: 0, monthSpentCny: 0, quotaProgressPercent: 0, remainingQuotaCny: 0 } })
    if (String(url).endsWith('/billing/providers')) return envelope([{ provider: 'DEEPSEEK', displayName: 'Synthetic legacy provider', configuredKeyCount: 0, usedCny: 0, remainingCny: 500, monthlyBudgetCny: 500, providerReportedBalance: providerValue, progressPercent: 0 }])
    throw new Error(`Unexpected overview request ${url}`)
  }
  authStore.state.user = { id: 1001, displayName: 'Synthetic help reader', permissions: ['billing:read', 'billing:read:any'] }
  app = createApp(OverviewView).use(router); app.mount(fixture); await settle(); await settle()
  const overview = fixture.textContent
  assert(overview.includes('平台沙箱余额') && overview.includes('ADMIN · LEGACY BUDGET'), 'Overview labels sandbox wallet and legacy budget explicitly')
  assert(overview.includes('与个人 BYOK 用量和账单无关') && overview.includes('当前新实验为 DEMO 合成结果'), 'Overview distinguishes personal AI from demo experiments and old budgets')
  assert(!overview.includes('LIVE BUDGET') && !overview.includes('真实模型已经接入') && !overview.includes('round robin'), 'Overview no longer advertises live shared-model readiness')
  assert(overview.includes('本地月度预算') && overview.includes('¥500.00'), 'Missing legacy upstream balance is honestly shown as a local budget')
  app.unmount()
  providerValue = 12.34
  app = createApp(OverviewView).use(router); app.mount(fixture); await settle(); await settle()
  const legacyValue = Array.from(fixture.querySelectorAll('.home-provider-values span')).find(el => el.textContent.includes('币种待核对'))
  assert(legacyValue?.querySelector('b')?.textContent === '12.34', 'Legacy upstream balance is not relabeled as CNY when the API omits its currency')
  app.unmount(); authStore.state.user = null
  document.getElementById('results').textContent = `PASS ${passed.length}\n` + passed.join('\n')
  document.documentElement.dataset.result = 'passed'
} catch (error) {
  document.getElementById('results').textContent = `FAIL: ${error.stack}\nPassed:\n` + passed.join('\n')
  document.documentElement.dataset.result = 'failed'
} finally { if (fixture.children.length) app?.unmount() }
