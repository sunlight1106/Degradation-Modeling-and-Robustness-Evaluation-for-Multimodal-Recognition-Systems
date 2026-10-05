import { createApp, h, nextTick } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import NotesView from '../views/NotesView.vue'
import NoteEditorView from '../views/NoteEditorView.vue'
import { renderNote } from '../lib/markdown.ts'
import { api, tokenStorage } from '../api/client.ts'
import { authStore } from '../stores/auth.ts'

const fixture = document.getElementById('fixture'), passed = []
const assert = (condition, message) => { if (!condition) throw new Error(message); passed.push(message) }
const wait = async predicate => { for (let i = 0; i < 150; i++) { await new Promise(resolve => setTimeout(resolve, 5)); await nextTick(); if (predicate()) return } throw new Error('UI wait timed out') }
const envelope = data => new Response(JSON.stringify({ success: true, data }), { headers: { 'Content-Type': 'application/json' } })
const sample = (id, library) => ({ id, title: `Learning ${id}`, body: '<h2>Reading</h2><p>Hello</p>', contentFormat: 'HTML', library, tags: ['复习'], excerpt: 'Reading Hello', status: { code: 'DRAFT', label: '草稿' }, references: [], shareCount: 0, createdAt: '2026-10-05T00:00:00Z', updatedAt: '2026-10-05T00:00:00Z' })
const notes = [sample('english', '英语学习'), sample('computer', '计算机学习'), sample('custom', '天文学')]
const saves = [], exports = []
window.fetch = async (url, init = {}) => {
  const path = String(url)
  if (path === '/api/v1/notes') return envelope(notes)
  if (path.endsWith('/shares')) return envelope([])
  if (path === '/api/v1/notes/english') {
    if (init.method === 'PATCH') {
      const body = JSON.parse(init.body); saves.push(body)
      Object.assign(notes[0], body, { status: { code: body.status, label: '草稿' }, tags: body.tags.split(',').filter(Boolean) })
    }
    return envelope(notes[0])
  }
  return envelope([])
}
api.exportNote = async (id, format, filename) => { exports.push({ id, format, filename }) }
let app
try {
  const safe = renderNote('<h2 onclick="bad()">Heading</h2><script>bad()</script><iframe src="https://evil.test"></iframe><img src="https://evil.test/track"><a href="javascript:bad()">bad</a><style>body{display:none}</style>', 'HTML')
  assert(safe.includes('<h2>Heading</h2>') && !/onclick|<script|<iframe|<img|javascript:|<style/.test(safe), 'HTML preview retains headings and removes executable or remote content')
  assert(renderNote('```html\n<script>example()</script>\n```').includes('&lt;script&gt;'), 'HTML examples in Markdown code blocks stay literal')
  tokenStorage.set('synthetic-learning-session')
  authStore.state.user = { id: 1, roleCode: 'RESEARCHER', permissions: ['note:read', 'note:write'] }
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/notes', name: 'notes', component: NotesView }, { path: '/notes/new', name: 'note-create', component: NoteEditorView },
    { path: '/notes/:id/edit', name: 'note-edit', component: NoteEditorView }, { path: '/knowledge', name: 'knowledge', component: { template: '<div />' } },
    { path: '/app/:pathMatch(.*)*', component: { template: '<div />' } },
  ] })
  await router.push('/notes?library=英语学习'); await router.isReady()
  app = createApp({ render: () => h(RouterView) }); app.use(router); app.mount(fixture)
  await wait(() => fixture.querySelector('.learning-note-row'))
  assert(fixture.querySelectorAll('.learning-note-row').length === 1 && fixture.textContent.includes('Learning english'), 'English library has a separate filtered list')
  assert(fixture.querySelector('.learning-sidebar').textContent.includes('天文学'), 'Saved custom library is included in the index')
  assert([...fixture.querySelectorAll('a')].some(link => link.getAttribute('href') === '/app/vocabulary'), 'English notes link to vocabulary practice')
  await router.push('/notes?library=计算机学习'); await nextTick()
  assert(fixture.querySelector('.learning-note-row').textContent.includes('Learning computer'), 'Changing library updates the visible list')
  await router.push('/notes/english/edit')
  await wait(() => fixture.querySelector('textarea')?.value.includes('<h2>Reading') && !fixture.querySelector('textarea').disabled)
  assert(fixture.querySelector('.note-preview-pane h2')?.textContent === 'Reading', 'Saved HTML note opens with a rendered right pane')
  const area = fixture.querySelector('textarea')
  area.value = '<h2>Updated</h2><p>学习记录</p><script>bad()</script>'; area.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  assert(fixture.querySelector('.note-preview-pane h2')?.textContent === 'Updated' && !fixture.querySelector('.note-preview-pane script'), 'Preview follows edits without executing script')
  const libraryInput = fixture.querySelector('input[list="note-libraries"]')
  libraryInput.value = '天文学'; libraryInput.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  const save = [...fixture.querySelectorAll('button')].find(button => button.textContent.includes('保存更改')); save.click()
  await wait(() => saves.length === 1 && !fixture.querySelector('textarea').disabled)
  assert(saves[0].library === '天文学' && saves[0].contentFormat === 'HTML', 'Save persists custom library and content format')
  for (const label of ['Markdown', 'PDF', 'Word', 'HTML', 'TXT']) {
    [...fixture.querySelector('.note-editor-export').querySelectorAll('button')].find(button => button.textContent === label).click()
    await wait(() => exports.length === ['Markdown', 'PDF', 'Word', 'HTML', 'TXT'].indexOf(label) + 1)
    await nextTick()
  }
  assert(exports.map(item => item.format).join(',') === 'md,pdf,docx,html,txt', 'All five export actions request the correct format')
  const format = [...fixture.querySelectorAll('select')].find(select => [...select.options].some(option => option.value === 'HTML'))
  format.value = 'MARKDOWN'; format.dispatchEvent(new Event('change', { bubbles: true })); await nextTick()
  assert(fixture.querySelector('.note-md-tools'), 'Markdown formatting tools return when switching modes')
  window.confirm = () => false
  await router.push('/notes')
  assert(router.currentRoute.value.name === 'note-edit', 'Changing format marks the note dirty and protects unsaved changes')
  window.confirm = () => true
  await router.push('/notes/new?library=计算机学习&template=study')
  await wait(() => fixture.querySelector('textarea')?.value.includes('def total'))
  assert(fixture.querySelector('input[list="note-libraries"]').value === '计算机学习', 'New computer note includes its library and runnable code example as a template')
  document.documentElement.dataset.result = 'passed'; document.getElementById('results').textContent = `PASS (${passed.length} assertions)`
} catch (error) {
  document.documentElement.dataset.result = 'failed'; document.getElementById('results').textContent = error.stack || String(error)
} finally { app?.unmount(); tokenStorage.clear(); authStore.state.user = null }
