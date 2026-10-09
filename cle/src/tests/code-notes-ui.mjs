import { createApp, h, nextTick } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import NoteEditorView from '../views/NoteEditorView.vue'
import { codeFences, fencedCode, replaceFence, highlightCode } from '../lib/codeBlocks.ts'
import { authStore } from '../stores/auth.ts'
import { tokenStorage } from '../api/client.ts'
const fixture = document.getElementById('fixture'), passed = []
const assert = (ok, label) => { if (!ok) throw new Error(label); passed.push(label) }
const wait = async predicate => { for (let i = 0; i < 150; i++) { await new Promise(resolve => setTimeout(resolve, 5)); await nextTick(); if (predicate()) return } throw new Error('UI wait timed out') }
const envelope = data => new Response(JSON.stringify({ success: true, data }), { headers: { 'Content-Type': 'application/json' } })
const button = label => [...fixture.querySelectorAll('button')].find(node => node.textContent.includes(label))
async function set(node, value, event = 'input') { node.value = value; node.dispatchEvent(new Event(event, { bubbles: true })); await nextTick() }
const original = 'int total = 2 + 3;\n// total is used below\nSystem.out.println(total);'
const note = { id: 'one', title: 'Code notebook', body: '# Keep this introduction\n\n' + fencedCode(original, 'java') + '\nKeep this conclusion.', library: '计算机学习', contentFormat: 'MARKDOWN', parentId: null, tags: [], status: { code: 'DRAFT', label: '草稿' }, references: [], shareCount: 0, createdAt: '2026-10-05T00:00:00Z', updatedAt: '2026-10-05T00:00:00Z' }
const previews = [], executions = [], saves = []
let app
window.fetch = async (url, init = {}) => {
  const path = String(url)
  if (path.endsWith('/notes/one/shares') || path.endsWith('/notes/sources/experiments')) return envelope([])
  if (path.endsWith('/notes/one')) {
    if (init.method === 'PATCH') { const body = JSON.parse(init.body); saves.push(body); return envelope({ ...note, ...body, status: note.status, tags: [] }) }
    return envelope(note)
  }
  if (path.endsWith('/notes')) return envelope([note])
  if (path.endsWith('/personal-ai/settings')) return envelope([{ provider: 'OPENAI', model: 'synthetic', enabled: true, configured: true }])
  if (path.endsWith('/personal-ai/providers')) return envelope([{ provider: 'OPENAI', remoteEnabled: true }])
  if (path.endsWith('/personal-ai/preview')) {
    const body = JSON.parse(init.body); previews.push(body)
    return envelope({ previewToken: 'synthetic-one-shot', expiresAt: new Date(Date.now() + 60000).toISOString(), provider: 'OPENAI', model: 'synthetic', action: body.action, context: body.body, systemPrompt: 'Static analysis only', outboundBytes: 250 })
  }
  if (path.endsWith('/personal-ai/execute')) { executions.push(JSON.parse(init.body)); return envelope({ action: 'code-annotate', engine: 'PERSONAL_AI:OPENAI', result: '// AI 静态分析（未执行）\n// 未提供 main；静态推测打印 5。\n' + original, items: [], persistenceStatus: 'SAVED', inputTokens: 20, outputTokens: 30 }) }
  throw new Error('Unexpected request: ' + path)
}
try {
  const source = 'Before\n' + fencedCode('print("```")', 'python') + '\nAfter'
  const block = codeFences(source)[0]
  assert(block.code === 'print("```")', 'Backticks inside code do not terminate the fenced block')
  assert(replaceFence(source, block, 'pass', 'python').startsWith('Before\n') && replaceFence(source, block, 'pass').endsWith('\nAfter'), 'Replacing a block preserves surrounding note content')
  const colored = highlightCode(original, 'java')
  assert(colored.includes('token keyword') && colored.includes('token comment'), 'Java keywords and comments have distinct syntax tokens')
  assert((colored.match(/data-symbol="total"/g) || []).length === 2, 'Same-name identifier tokens exclude comments')
  assert(!highlightCode('<script>alert(1)</script>', 'html').includes('<script>'), 'Code markup is escaped before highlighting')
  assert(!highlightCode('x'.repeat(31000), 'java').includes('<span'), 'Long code uses bounded plain-text fallback')
  authStore.state.user = { id: 1, roleCode: 'RESEARCHER', permissions: ['note:read', 'note:write', 'personal-ai:use'] }; tokenStorage.set('synthetic-code-test')
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/notes/:id', name: 'note-edit', component: NoteEditorView }, { path: '/notes', name: 'notes', component: { template: '<div />' } }, { path: '/app/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/notes/one'); await router.isReady(); app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(fixture)
  await wait(() => fixture.querySelector('.code-symbol') && !fixture.querySelector('textarea').disabled)
  const identifier = [...fixture.querySelectorAll('.code-symbol')].find(node => node.dataset.symbol === 'total')
  identifier.dispatchEvent(new Event('pointerover', { bubbles: true })); await nextTick()
  assert(fixture.querySelectorAll('.code-symbol-active').length === 2, 'Hover highlights both uses of the same identifier')
  identifier.dispatchEvent(new Event('pointerout', { bubbles: true })); await nextTick()
  assert(!fixture.querySelector('.code-symbol-active'), 'Identifier highlighting clears as soon as the pointer leaves')
  await set(fixture.querySelector('select[aria-label="选择代码块"]'), '0', 'change')
  assert(fixture.querySelector('textarea').value === original, 'Block mode edits only the selected code')
  await wait(() => [...fixture.querySelectorAll('option')].some(option => option.value === 'OPENAI'))
  const model = [...fixture.querySelectorAll('select')].find(select => [...select.options].some(option => option.value === 'OPENAI'))
  await set(model, 'OPENAI', 'change'); button('AI 解释并生成注释').click()
  await wait(() => fixture.querySelector('[aria-label="发送前确认"]'))
  assert(previews[0].body === original && previews[0].codeLanguage === 'java' && previews[0].selectedTaskIds.length === 0, 'AI preview sends only the selected code and language')
  assert(executions.length === 0, 'Preview performs no paid execution')
  await set(fixture.querySelector('textarea'), original + '\n// edited')
  assert(!fixture.querySelector('[aria-label="发送前确认"]'), 'Editing the block invalidates prior AI consent')
  await set(fixture.querySelector('textarea'), original); button('AI 解释并生成注释').click()
  await wait(() => fixture.querySelector('[aria-label="发送前确认"]'))
  const consent = fixture.querySelector('[aria-label="发送前确认"] input[type="checkbox"]'); consent.checked = true; consent.dispatchEvent(new Event('change', { bubbles: true })); await nextTick()
  button('确认发送并生成').click(); await wait(() => button('把注释应用到当前代码块'))
  assert(executions.length === 1 && fixture.querySelector('textarea').value === original, 'One confirmed call produces a preview without modifying code')
  button('把注释应用到当前代码块').click(); await nextTick()
  assert(fixture.querySelector('textarea').value.endsWith(original) && fixture.querySelector('textarea').value.includes('未执行'), 'Applying annotations retains the original code and unexecuted label')
  await set(fixture.querySelector('select[aria-label="选择代码块"]'), '-1', 'change')
  assert(fixture.querySelector('textarea').value.startsWith('# Keep this introduction') && fixture.querySelector('textarea').value.endsWith('Keep this conclusion.'), 'Annotation application preserves the rest of the notebook')
  button('保存更改').click(); await wait(() => saves.length === 1)
  assert(saves[0].body.includes('未提供 main'), 'Annotation and missing-entry note persist in the ordinary save payload')
  document.documentElement.dataset.result = 'passed'; document.getElementById('results').textContent = `PASS (${passed.length} assertions)`
} catch (error) { document.documentElement.dataset.result = 'failed'; document.getElementById('results').textContent = error.stack || String(error) }
finally { app?.unmount(); tokenStorage.clear(); authStore.state.user = null }
