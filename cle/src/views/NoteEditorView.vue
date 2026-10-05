<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue'
import { RouterLink, useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import { personalApi } from '@/api/personal'
import type {
  FileView,
  KnowledgeEntryView,
  NoteExportFormat,
  NoteContentFormat,
  NoteReferenceType,
  NoteReferenceView,
  NoteShareView,
  NoteStatusCode,
  NoteView,
} from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import NotePreview from '@/components/NotePreview.vue'
import { codeFences, codeLanguages, fencedCode, replaceFence } from '@/lib/codeBlocks'
import type { NoteSummaryView } from '@/types/api'
import NotePersonalTools from '@/components/NotePersonalTools.vue'
import { authStore } from '@/stores/auth'
import { toastStore } from '@/stores/toast'
import { noteLibraries, noteTemplates } from '@/lib/noteLibraries'
import { countWords, extractOutline, readingMinutes, renderNote } from '@/lib/markdown'

const route = useRoute()
const router = useRouter()

const canWrite = computed(() => authStore.has('note:write'))

const statusOptions: Array<{ code: NoteStatusCode; label: string }> = [
  { code: 'DRAFT', label: '草稿' },
  { code: 'ACTIVE', label: '已定稿' },
  { code: 'ARCHIVED', label: '已归档' },
]

const currentId = ref<string | null>(null)
const title = ref('')
const body = ref('')
const tagsInput = ref('')
const library = ref('综合学习')
const contentFormat = ref<NoteContentFormat>('MARKDOWN')
const status = ref<NoteStatusCode>('DRAFT')
const parentId = ref('')
const pageOptions = ref<NoteSummaryView[]>([])
const pageOptionsError = ref('')
const selectedCodeIndex = ref(-1)
const commentStyle = ref('auto')
const blockMenuOpen = ref(false)
const blocks = computed(() => contentFormat.value === 'MARKDOWN' ? codeFences(body.value) : [])
const selectedBlock = computed(() => blocks.value[selectedCodeIndex.value])
const editorText = computed({
  get: () => selectedBlock.value?.code ?? body.value,
  set: value => { body.value = selectedBlock.value ? replaceFence(body.value, selectedBlock.value, value) : value },
})
const parentPage = computed(() => pageOptions.value.find(page => page.id === parentId.value))
const childPages = computed(() => currentId.value ? pageOptions.value.filter(page => page.parentId === currentId.value) : [])
const annotationUndo = ref<{ before: string; after: string } | null>(null)
async function loadPageOptions() {
  try { pageOptions.value = await api.notes(); pageOptionsError.value = '' }
  catch { pageOptionsError.value = '页面目录暂时无法加载，请刷新后重试。' }
}
function setCodeLanguage(event: Event) {
  if (selectedBlock.value) body.value = replaceFence(body.value, selectedBlock.value, selectedBlock.value.code, (event.target as HTMLSelectElement).value)
}
function insertBlock(kind: string) {
  const snippets: Record<string, string> = {
    heading: '## 小节标题\n', list: '- 列表项\n', todo: '- [ ] 待办事项\n', quote: '> 提示或引用\n',
    table: '| 项目 | 说明 |\n| --- | --- |\n| 内容 | 内容 |\n', divider: '---\n',
    code: fencedCode('int total = 2 + 3;\nSystem.out.println(total);', 'java'),
  }
  const insertion = snippets[kind]
  if (!insertion) return
  const start = selectedBlock.value?.end ?? bodyEl.value?.selectionStart ?? body.value.length
  const end = selectedBlock.value?.end ?? bodyEl.value?.selectionEnd ?? start
  const text = '\n\n' + insertion + '\n'
  body.value = body.value.slice(0, start) + text + body.value.slice(end)
  selectedCodeIndex.value = -1; blockMenuOpen.value = false
  if (kind === 'code') selectedCodeIndex.value = codeFences(body.value).findIndex(block => block.start >= start)
  nextTick(() => { bodyEl.value?.focus() })
}
function applyAssisted(text: string) {
  const block = selectedBlock.value
  if (block) {
    const updated = replaceFence(body.value, block, text, block.language === 'json' ? 'jsonc' : block.language)
    if (updated.length > 200000) { toastStore.error('添加注释后超过笔记长度限制，请拆分笔记'); return }
    annotationUndo.value = { before: body.value, after: updated }
    body.value = updated
  } else body.value = text
}
watch(contentFormat, () => { selectedCodeIndex.value = -1; blockMenuOpen.value = false })
const references = ref<NoteReferenceView[]>([])
const shareCount = ref(0)
const updatedAt = ref<string | null>(null)

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const dirty = ref(false)
const ready = ref(false)

const bodyEl = ref<HTMLTextAreaElement | null>(null)

const previewHtml = computed(() => renderNote(body.value, contentFormat.value))
const outline = computed(() => contentFormat.value === 'HTML' ? [] : extractOutline(body.value))
const wordCount = computed(() => countWords(body.value))
const readMinutes = computed(() => readingMinutes(body.value))
const isNew = computed(() => currentId.value === null)

const tagList = computed(() =>
  tagsInput.value.split(/[,，、;；\s]+/).map(item => item.trim()).filter(Boolean),
)

function errText(reason: unknown, fallback: string) {
  return reason instanceof ApiClientError ? reason.message : fallback
}

function formatDate(value: string | null) {
  if (!value) return ''
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

watch([title, body, tagsInput, status, library, contentFormat, parentId], () => {
  if (ready.value) dirty.value = true
})

function applyNote(note: NoteView) {
  currentId.value = note.id
  parentId.value = note.parentId || ''
  library.value = note.library || "综合学习"
  contentFormat.value = note.contentFormat || "MARKDOWN"
  title.value = note.title
  body.value = note.body
  tagsInput.value = note.tags.join(', ')
  status.value = note.status.code
  references.value = note.references
  shareCount.value = note.shareCount
  updatedAt.value = note.updatedAt
  dirty.value = false
}

let loadVersion = 0
async function load() {
  const version = ++loadVersion
  const raw = route.params.id
  if (typeof raw !== 'string' || !raw) {
    parentId.value = typeof route.query.parent === 'string' ? route.query.parent : ''
    library.value = typeof route.query.library === 'string' ? route.query.library.slice(0, 40) : '综合学习'
    if (route.query.template === 'study') useTemplate()
    if (typeof route.query.entry === 'string') {
      try {
        const entry = await api.knowledgeEntry(route.query.entry)
        if (version !== loadVersion) return
        title.value = entry.title; body.value = entry.body; library.value = entry.domain; tagsInput.value = entry.tags.join(', ')
      } catch (reason) { toastStore.error(errText(reason, '知识卡读取失败')) }
    }
    await nextTick()
    dirty.value = false
    ready.value = true
    return
  }
  loading.value = true
  ready.value = false
  error.value = ''
  try {
    const note = await api.note(raw)
    if (version !== loadVersion) return
    applyNote(note)
    const loadedShares = await api.noteShares(raw)
    if (version === loadVersion) shares.value = loadedShares
  } catch (reason) {
    if (version === loadVersion) error.value = errText(reason, '笔记加载失败')
  } finally {
    if (version === loadVersion) {
      await nextTick()
      loading.value = false
      ready.value = !error.value
      dirty.value = false
    }
  }
}

async function save(): Promise<string | null> {
  if (!canWrite.value || saving.value || loading.value || !ready.value) return currentId.value
  if (!title.value.trim()) {
    toastStore.error('请先填写标题')
    return null
  }
  if (body.value.length > 200000) { toastStore.error("正文超过 200,000 字符，请拆分笔记"); return null }
  const version = loadVersion
  saving.value = true
  try {
    const payload = {
      title: title.value.trim(),
      body: body.value,
      tags: tagsInput.value.trim(),
      status: status.value,
      library: library.value.trim() || "综合学习",
      contentFormat: contentFormat.value,
      parentId: parentId.value,
    }
    const wasNew = isNew.value
    const saved = currentId.value
      ? await api.updateNote(currentId.value, payload)
      : await api.createNote(payload)
    if (version !== loadVersion || !ready.value) return null
    applyNote(saved)
    await nextTick()
    dirty.value = false
    toastStore.success('已保存')
    if (wasNew) await router.replace({ name: 'note-edit', params: { id: saved.id } })
    return saved.id
  } catch (reason) {
    if (version === loadVersion) toastStore.error(errText(reason, '保存失败'))
    return null
  } finally {
    saving.value = false
  }
}

async function ensureSaved(): Promise<string | null> {
  if (currentId.value && !dirty.value) return currentId.value
  return save()
}

async function remove() {
  if (!currentId.value) return
  if (!window.confirm(`删除笔记「${title.value || '未命名'}」？此操作不可撤销。`)) return
  try {
    await api.deleteNote(currentId.value)
    toastStore.success('笔记已删除')
    router.push({ name: 'notes' })
  } catch (reason) {
    toastStore.error(errText(reason, '删除失败'))
  }
}

// --- Markdown 快捷输入 ------------------------------------------------------
function surround(before: string, after = before) {
  const el = bodyEl.value
  if (!el) return
  const start = el.selectionStart
  const end = el.selectionEnd
  const selected = body.value.slice(start, end)
  body.value = body.value.slice(0, start) + before + selected + after + body.value.slice(end)
  requestAnimationFrame(() => {
    el.focus()
    el.setSelectionRange(start + before.length, start + before.length + selected.length)
  })
}

function prefixLine(prefix: string) {
  const el = bodyEl.value
  if (!el) return
  const start = el.selectionStart
  const lineStart = body.value.lastIndexOf('\n', start - 1) + 1
  body.value = body.value.slice(0, lineStart) + prefix + body.value.slice(lineStart)
  requestAnimationFrame(() => {
    el.focus()
    el.setSelectionRange(start + prefix.length, start + prefix.length)
  })
}

const tools: Array<{ label: string; title: string; run: () => void }> = [
  { label: 'B', title: '加粗', run: () => surround('**') },
  { label: 'I', title: '斜体', run: () => surround('*') },
  { label: 'H2', title: '标题', run: () => prefixLine('## ') },
  { label: '•', title: '无序列表', run: () => prefixLine('- ') },
  { label: '❝', title: '引用', run: () => prefixLine('> ') },
  { label: '</>', title: '行内代码', run: () => surround('`') },
]

function useTemplate() {
  if (body.value.trim() && !window.confirm("用学习模板替换当前正文？")) return
  const template = noteTemplates[library.value] || noteTemplates["综合学习"]!
  title.value = title.value || template.title
  body.value = template.body
  contentFormat.value = "MARKDOWN"
}

function onBodyKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') blockMenuOpen.value = false
  if (event.key === '/' && contentFormat.value === 'MARKDOWN' && !selectedBlock.value) {
    const start = bodyEl.value?.selectionStart ?? 0
    if (!body.value.slice(body.value.lastIndexOf('\n', start - 1) + 1, start).trim()) {
      event.preventDefault(); blockMenuOpen.value = true; return
    }
  }

  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    void save()
  }
}

// Assistance changes only the local editor. Saving stays explicit.
function appendToBody(text: string) { body.value = `${body.value.trimEnd()}\n\n${text.trim()}\n`.trimStart() }
function applyTags(items: string[]) { tagsInput.value = Array.from(new Set([...tagList.value, ...items])).join(', ') }

// --- 导出 -------------------------------------------------------------------
const exporting = ref<NoteExportFormat | null>(null)

async function exportAs(format: NoteExportFormat) {
  if (exporting.value || saving.value || loading.value || !ready.value) return
  exporting.value = format
  try {
    const id = await ensureSaved()
    if (!id) return
    const base = (title.value.trim() || '笔记').replace(/[\\/:*?"<>|]/g, '_').slice(0, 60)
    await api.exportNote(id, format, `${base}.${format}`)
    toastStore.success(`已导出 ${format.toUpperCase()}`)
  } catch (reason) {
    toastStore.error(errText(reason, '导出失败'))
  } finally {
    exporting.value = null
  }
}

// --- 分享 -------------------------------------------------------------------
const shares = ref<NoteShareView[]>([])
const shareLabel = ref('')
const shareDays = ref<number | null>(30)
const shareBusy = ref(false)

async function createShare() {
  const id = await ensureSaved()
  if (!id) return
  shareBusy.value = true
  try {
    const share = await api.createNoteShare(id, {
      label: shareLabel.value.trim() || undefined,
      expiresInDays: shareDays.value ?? undefined,
    })
    shares.value = [share, ...shares.value]
    shareCount.value += 1
    shareLabel.value = ''
    await copyText(share.shareUrl)
    toastStore.success('分享链接已创建并复制')
  } catch (reason) {
    toastStore.error(errText(reason, '创建分享失败'))
  } finally {
    shareBusy.value = false
  }
}

async function revokeShare(share: NoteShareView) {
  if (!currentId.value) return
  try {
    await api.revokeNoteShare(currentId.value, share.id)
    shares.value = shares.value.map(item => (item.id === share.id ? { ...item, active: false } : item))
    toastStore.success('分享已撤销')
  } catch (reason) {
    toastStore.error(errText(reason, '撤销失败'))
  }
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    return
  } catch {
    // 降级到 execCommand
  }
  const area = document.createElement('textarea')
  area.value = text
  document.body.appendChild(area)
  area.select()
  try {
    document.execCommand('copy')
  } catch {
    toastStore.error('复制失败，请手动复制')
  }
  area.remove()
}

// --- 引用 -------------------------------------------------------------------
const refOpen = ref(false)
const refType = ref<NoteReferenceType>('ENTRY')
const refPick = ref('')
const refLabel = ref('')
const refBusy = ref(false)
let refVersion = 0
const refOptions = ref<Array<{ value: string; label: string }>>([])

const refTypeOptions: Array<{ code: NoteReferenceType; label: string }> = [
  { code: 'ENTRY', label: '知识卡' },
  { code: 'FILE', label: '文件素材' },
  { code: 'TASK', label: '推理任务' },
]

async function openReferences() {
  refOpen.value = true
  await loadRefOptions()
}

watch(refType, () => { void loadRefOptions() })

async function loadRefOptions() {
  const version = ++refVersion
  refPick.value = ''
  refOptions.value = []
  try {
    if (refType.value === 'ENTRY') {
      const list: KnowledgeEntryView[] = await api.knowledgeEntries({})
      if (version !== refVersion) return
      refOptions.value = list.map(item => ({ value: item.id, label: `${item.title}（${item.domain} · ${item.topicName}）` }))
    } else if (refType.value === 'FILE') {
      const list: FileView[] = await api.files()
      if (version !== refVersion) return
      refOptions.value = list.map(item => ({ value: item.id, label: item.originalName }))
    } else {
      const list = await personalApi.experiments()
      if (version !== refVersion) return
      refOptions.value = list.map(item => ({ value: item.taskId, label: item.title }))
    }
  } catch (reason) {
    toastStore.error(errText(reason, '引用目标加载失败'))
  }
}

async function addReference() {
  const id = await ensureSaved()
  if (!id) return
  if (!refPick.value) {
    toastStore.error('请选择要引用的对象')
    return
  }
  refBusy.value = true
  try {
    const note = await api.addNoteReference(id, {
      referenceType: refType.value,
      referenceId: refPick.value,
      label: refLabel.value.trim() || undefined,
    })
    references.value = note.references
    refOpen.value = false
    refLabel.value = ''
    toastStore.success('引用已添加')
  } catch (reason) {
    toastStore.error(errText(reason, '添加引用失败'))
  } finally {
    refBusy.value = false
  }
}

async function removeReference(reference: NoteReferenceView) {
  if (!currentId.value) return
  try {
    const note = await api.removeNoteReference(currentId.value, reference.id)
    references.value = note.references
    toastStore.success('引用已移除')
  } catch (reason) {
    toastStore.error(errText(reason, '移除失败'))
  }
}

const refTypeLabel: Record<string, string> = { FILE: '文件', TASK: '任务', ENTRY: '知识卡' }

function confirmLeave() {
  if (!authStore.state.user) return true
  if (saving.value) { toastStore.info('正在保存，请稍候再离开'); return false }
  return !dirty.value || window.confirm('笔记有未保存的更改。离开会丢弃这些更改，继续？')
}
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate((to, from) => {
  if (to.params.id !== from.params.id && to.params.id !== currentId.value && !confirmLeave()) return false
})
watch(() => route.params.id, (next, previous) => {
  if (next === previous || (typeof next === 'string' && next === currentId.value)) return
  annotationUndo.value = null; selectedCodeIndex.value = -1; parentId.value = ''; ready.value = false; currentId.value = null; title.value = ''; body.value = ''; tagsInput.value = ''; status.value = 'DRAFT'; references.value = []; shares.value = []; shareCount.value = 0; updatedAt.value = null; dirty.value = false
  void load()
})
function warnUnload(event: BeforeUnloadEvent) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => { void load(); void loadPageOptions(); window.addEventListener('beforeunload', warnUnload) })
onBeforeUnmount(() => { loadVersion++; refVersion++; window.removeEventListener('beforeunload', warnUnload) })
</script>

<template>
  <div class="page-stack note-editor-page">
    <section class="page-intro page-intro--split">
      <div>
        <p class="page-kicker">NOTE EDITOR</p>
        <h2>{{ isNew ? '新建笔记' : '编辑笔记' }}</h2>
        <p>按学习库整理笔记。左侧编写 Markdown 或 HTML，右侧实时预览，支持代码块、表格和文档导出。</p>
      </div>
      <div class="intro-actions note-editor-head">
        <RouterLink :to="{ name: 'notes', query: { library } }" class="button button--ghost">
          <AppIcon name="chevron" :size="16" /> 返回列表
        </RouterLink>
        <button v-if="!isNew" class="button button--ghost note-danger" @click="remove">
          <AppIcon name="trash" :size="16" /> 删除
        </button>
        <button class="button button--dark" :disabled="saving || !canWrite" @click="save">
          <AppIcon name="check" :size="16" /> {{ saving ? '保存中…' : dirty ? '保存更改' : '已保存' }}
        </button>
      </div>
    </section>

    <nav v-if="parentPage || childPages.length" class="note-page-path" aria-label="页面关联">
      <RouterLink v-if="parentPage" :to="{ name: 'note-edit', params: { id: parentPage.id } }">上级：{{ parentPage.title }}</RouterLink>
      <RouterLink v-for="child in childPages" :key="child.id" :to="{ name: 'note-edit', params: { id: child.id } }">子页：{{ child.title }}</RouterLink>
    </nav>
    <p v-if="error" class="inline-alert inline-alert--error">{{ error }}</p>

    <section class="panel note-editor-meta">
      <div class="note-editor-fields">
        <input v-model="title" :disabled="loading || saving || !ready" class="field-input note-title-input" maxlength="180" placeholder="无标题笔记" />
        <div class="note-editor-row">
          <label class="field-label">学习库<input v-model="library" :disabled="loading || saving || !ready" class="field-input" list="note-libraries" maxlength="40" placeholder="选择或输入自定义库名" /><datalist id="note-libraries"><option v-for="name in noteLibraries" :key="name" :value="name" /></datalist></label>
          <label class="field-label">编写格式<select v-model="contentFormat" :disabled="loading || saving || !ready" class="field-input"><option value="MARKDOWN">Markdown</option><option value="HTML">HTML</option></select></label>
          <label class="field-label note-status-field">
            状态
            <select v-model="status" :disabled="loading || saving || !ready" class="field-input">
              <option v-for="option in statusOptions" :key="option.code" :value="option.code">{{ option.label }}</option>
            </select>
          </label>
          <label class="field-label note-tags-field">
            标签（逗号分隔）
            <input v-model="tagsInput" :disabled="loading || saving || !ready" class="field-input" maxlength="500" placeholder="如：英语写作, Python, 复习" />
          </label>
        </div>
      </div>
      <label class="field-label note-parent-field">父页面
        <select v-model="parentId" class="field-input" :disabled="loading || saving || !ready"><option value="">无 · 顶层页面</option><option v-for="page in pageOptions.filter(page => page.id !== currentId)" :key="page.id" :value="page.id">{{ page.title }} · {{ page.library }}</option></select>
        <span v-if="pageOptionsError" class="field-hint">{{ pageOptionsError }}</span>
      </label>
      <div class="note-editor-stats">
        <span><b>{{ wordCount }}</b> 字</span>
        <span>约 <b>{{ readMinutes }}</b> 分钟</span>
        <span v-if="updatedAt">更新于 {{ formatDate(updatedAt) }}</span>
        <span v-if="dirty" class="note-dirty">未保存</span>
      </div>
      <div class="note-editor-export">
        <span class="note-editor-export-label"><AppIcon name="export" :size="15" /> 导出</span>
        <button class="button button--ghost button--small" :disabled="exporting !== null || saving || loading || !ready" @click="exportAs('md')">Markdown</button>
        <button class="button button--ghost button--small" :disabled="exporting !== null || saving || loading || !ready" @click="exportAs('pdf')">PDF</button>
        <button class="button button--ghost button--small" :disabled="exporting !== null || saving || loading || !ready" @click="exportAs('docx')">Word</button>
        <button class="button button--ghost button--small" :disabled="exporting !== null || saving || loading || !ready" @click="exportAs('html')">HTML</button>
        <button class="button button--ghost button--small" :disabled="exporting !== null || saving || loading || !ready" @click="exportAs('txt')">TXT</button>
      </div>
    </section>



    <section class="note-editor-layout" :class="{ 'note-editor-layout--outline': outline.length > 0 }">
      <article class="panel note-editor-pane">
        <header class="note-pane-head">
          <strong>{{ contentFormat === "HTML" ? "HTML 源码" : "Markdown" }}</strong>
          <button class="button button--ghost button--small" :disabled="loading || saving || !ready" @click="useTemplate">插入学习模板</button>
          <div v-if="contentFormat === 'MARKDOWN' && !selectedBlock" class="note-md-tools">
            <button v-for="tool in tools" :key="tool.title" type="button" :disabled="loading || saving || !ready" :title="tool.title" @click="tool.run">{{ tool.label }}</button>
          </div>
        </header>
        <div v-if="contentFormat === 'MARKDOWN'" class="code-editor-toolbar">
          <label>编辑范围<select v-model.number="selectedCodeIndex" :disabled="loading || saving || !ready" aria-label="选择代码块"><option :value="-1">整篇笔记</option><option v-for="(block, index) in blocks" :key="index" :value="index">代码块 {{ index + 1 }} · {{ block.language }}</option></select></label>
          <label v-if="selectedBlock">语言<select :value="selectedBlock.language" :disabled="saving || loading" aria-label="代码语言" @change="setCodeLanguage"><option v-for="[value, label] in codeLanguages" :key="value" :value="value">{{ label }}</option></select></label>
          <button type="button" class="button button--ghost button--small" :disabled="saving || loading || !ready" @click="blockMenuOpen = !blockMenuOpen">＋ 插入块 <small>/</small></button>
        </div>
        <div v-if="blockMenuOpen" class="note-block-menu" aria-label="插入内容块"><button v-for="(label, kind) in { heading: '标题', list: '列表', todo: '待办', quote: '引用', table: '表格', divider: '分割线', code: '代码块' }" :key="kind" @click="insertBlock(kind)">{{ label }}</button><button @click="blockMenuOpen = false">取消</button></div>
        <p v-if="selectedBlock" class="code-editor-hint">只编辑此代码块；下方可选择个人模型生成注释。鼠标停在右侧标识符上可查看同名高亮。</p>
        <textarea
          ref="bodyEl"
          v-model="editorText"
          :disabled="loading || saving || !ready"
          class="note-editor-textarea"
          spellcheck="false"
          :placeholder="contentFormat === 'HTML' ? '<h2>学习目标</h2>\n<p>在这里开始记录。</p>' : '## 学习目标\n\n支持 Markdown、表格与 Python / HTML 等代码块。'"
          aria-label="笔记正文"
          maxlength="200000"
          @keydown="onBodyKeydown"
        />
      </article>

      <article class="panel note-preview-pane">
        <header class="note-pane-head">
          <strong>预览</strong>
          <span class="note-preview-hint">实时更新 · 仅排版，不执行脚本</span>
        </header>
        <NotePreview v-if="previewHtml" :body="body" :format="contentFormat" />
        <p v-else class="note-preview-empty">预览会随左侧输入实时更新。</p>
      </article>

      <aside v-if="outline.length" class="panel note-outline-pane">
        <header class="note-pane-head"><strong>大纲</strong></header>
        <nav class="note-outline">
          <span v-for="item in outline" :key="item.anchor" :class="`note-outline--l${item.level}`">{{ item.text }}</span>
        </nav>
      </aside>
    </section>

    <div v-if="selectedBlock" class="code-analysis-settings"><label>AI 注释方式<select v-model="commentStyle"><option value="auto">按语言自动选择</option><option value="line">单行注释</option><option value="block">多行注释</option></select></label><span>不支持多行注释的语言使用连续单行注释；JSON 注释后标为 JSONC。</span><button v-if="annotationUndo && body === annotationUndo.after" class="button button--ghost button--small" @click="body = annotationUndo.before; annotationUndo = null">撤销本次注释</button></div>
    <NotePersonalTools v-if="contentFormat === 'MARKDOWN'" :key="`${currentId || 'new'}:${selectedCodeIndex}`" :title="title" :body="selectedBlock?.code ?? body" :code-language="selectedBlock?.language" :comment-style="commentStyle" :disabled="loading || saving || !ready || !canWrite" @append="appendToBody" @replace="applyAssisted" @tags="applyTags" />

    <section class="panel note-refs-panel">
      <header class="panel-header">
        <div>
          <p class="page-kicker">REFERENCES</p>
          <h3>引用</h3>
          <p>把文件、推理任务或知识卡作为证据嵌入本篇笔记。</p>
        </div>
        <button class="button button--ghost button--small" @click="openReferences">
          <AppIcon name="plus" :size="15" /> 添加引用
        </button>
      </header>
      <div v-if="references.length" class="note-refs">
        <article v-for="reference in references" :key="reference.id" class="note-ref" :class="{ 'note-ref--blocked': !reference.accessible }">
          <span class="note-ref-kind">{{ refTypeLabel[reference.referenceType] || reference.referenceType }}</span>
          <div>
            <strong>{{ reference.displayTitle }}</strong>
            <small>{{ reference.displayMeta || (reference.accessible ? '' : '不可访问') }}</small>
          </div>
          <button class="icon-button" title="移除引用" @click="removeReference(reference)"><AppIcon name="close" :size="15" /></button>
        </article>
      </div>
      <p v-else class="note-refs-empty">还没有引用。点击「添加引用」把相关内容关联进来。</p>
    </section>

    <section class="panel note-share-panel">
      <header class="panel-header">
        <div>
          <p class="page-kicker">SHARING</p>
          <h3>分享</h3>
          <p>生成平台内只读链接（需登录），可设有效期并随时撤销。</p>
        </div>
        <span class="note-share-count">{{ shareCount }} 个链接</span>
      </header>
      <div class="note-share-create">
        <input v-model="shareLabel" class="field-input" maxlength="120" placeholder="备注（可选）" />
        <label class="note-share-days">
          有效期
          <select v-model.number="shareDays" class="field-input">
            <option :value="7">7 天</option>
            <option :value="30">30 天</option>
            <option :value="90">90 天</option>
            <option :value="365">365 天</option>
            <option :value="null">永久</option>
          </select>
        </label>
        <button class="button button--dark" :disabled="shareBusy" @click="createShare">
          <AppIcon name="share" :size="15" /> {{ shareBusy ? '创建中…' : '创建分享链接' }}
        </button>
      </div>
      <div v-if="shares.length" class="note-shares">
        <div v-for="share in shares" :key="share.id" class="note-share">
          <span class="note-share-state" :class="share.active ? 'is-active' : 'is-off'">{{ share.active ? '有效' : '已撤销' }}</span>
          <code>{{ share.shareUrl }}</code>
          <span class="note-share-views">{{ share.viewCount }} 次查看</span>
          <button class="table-action" @click="copyText(share.shareUrl)"><AppIcon name="copy" :size="14" /> 复制</button>
          <button v-if="share.active" class="table-action" @click="revokeShare(share)">撤销</button>
        </div>
      </div>
    </section>

    <div v-if="refOpen" class="modal-backdrop" @click.self="refOpen = false">
      <form class="modal-card" @submit.prevent="addReference">
        <header>
          <div><p class="page-kicker">ADD REFERENCE</p><h3>添加引用</h3></div>
          <button type="button" class="icon-button" @click="refOpen = false"><AppIcon name="close" /></button>
        </header>
        <label class="field-label">
          引用类型
          <select v-model="refType" class="field-input">
            <option v-for="option in refTypeOptions" :key="option.code" :value="option.code">{{ option.label }}</option>
          </select>
        </label>
        <label class="field-label">
          选择对象
          <select v-model="refPick" class="field-input" required>
            <option value="" disabled>请选择</option>
            <option v-for="option in refOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </label>
        <label class="field-label">备注（可选）<input v-model="refLabel" class="field-input" maxlength="180" placeholder="说明该引用在本文中的作用" /></label>
        <p class="field-hint">引用会以卡片形式展示，并标明来源；目标不可访问时会明确提示。</p>
        <button class="button button--dark button--full" :disabled="refBusy || !refOptions.length">
          {{ refBusy ? '添加中…' : refOptions.length ? '添加引用' : '暂无可引用对象' }}
        </button>
      </form>
    </div>
  </div>
</template>
