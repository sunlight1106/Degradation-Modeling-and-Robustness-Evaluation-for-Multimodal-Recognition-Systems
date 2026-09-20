<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type {
  FileView,
  InferenceView,
  KnowledgeEntryView,
  NoteAssistAction,
  NoteAssistResponse,
  NoteExportFormat,
  NoteReferenceType,
  NoteReferenceView,
  NoteShareView,
  NoteStatusCode,
  NoteView,
} from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import { authStore } from '@/stores/auth'
import { toastStore } from '@/stores/toast'
import { countWords, extractOutline, readingMinutes, renderMarkdown } from '@/lib/markdown'

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
const status = ref<NoteStatusCode>('DRAFT')
const references = ref<NoteReferenceView[]>([])
const shareCount = ref(0)
const updatedAt = ref<string | null>(null)

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const dirty = ref(false)
const ready = ref(false)

const bodyEl = ref<HTMLTextAreaElement | null>(null)

const previewHtml = computed(() => renderMarkdown(body.value))
const outline = computed(() => extractOutline(body.value))
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

watch([title, body, tagsInput, status], () => {
  if (ready.value) dirty.value = true
})

function applyNote(note: NoteView) {
  currentId.value = note.id
  title.value = note.title
  body.value = note.body
  tagsInput.value = note.tags.join(', ')
  status.value = note.status.code
  references.value = note.references
  shareCount.value = note.shareCount
  updatedAt.value = note.updatedAt
  dirty.value = false
}

async function load() {
  const raw = route.params.id
  if (typeof raw !== 'string' || !raw) {
    ready.value = true
    return
  }
  loading.value = true
  error.value = ''
  try {
    const note = await api.note(raw)
    applyNote(note)
    shares.value = await api.noteShares(raw)
  } catch (reason) {
    error.value = errText(reason, '笔记加载失败')
  } finally {
    loading.value = false
    ready.value = true
  }
}

async function save(): Promise<string | null> {
  if (!canWrite.value) return currentId.value
  if (!title.value.trim()) {
    toastStore.error('请先填写标题')
    return null
  }
  saving.value = true
  try {
    const payload = {
      title: title.value.trim(),
      body: body.value,
      tags: tagsInput.value.trim(),
      status: status.value,
    }
    const wasNew = isNew.value
    const saved = currentId.value
      ? await api.updateNote(currentId.value, payload)
      : await api.createNote(payload)
    applyNote(saved)
    toastStore.success('已保存')
    if (wasNew) await router.replace({ name: 'note-edit', params: { id: saved.id } })
    return saved.id
  } catch (reason) {
    toastStore.error(errText(reason, '保存失败'))
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

function onBodyKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    void save()
  }
}

// --- AI 整理 ----------------------------------------------------------------
const assistBusy = ref<NoteAssistAction | null>(null)
const assist = ref<NoteAssistResponse | null>(null)

const assistActions: Array<{ action: NoteAssistAction; label: string; icon: string }> = [
  { action: 'summarize', label: '摘要', icon: 'ai' },
  { action: 'outline', label: '大纲', icon: 'logs' },
  { action: 'tags', label: '标签', icon: 'link' },
  { action: 'tidy', label: '格式整理', icon: 'edit' },
]

const assistEngineLabel = computed(() => {
  const engine = assist.value?.engine
  if (!engine) return ''
  return engine === 'LOCAL_RULES' ? '本地规则算法' : engine.replace('MODEL:', '模型 ')
})

const assistIsLocal = computed(() => assist.value?.engine === 'LOCAL_RULES')

async function runAssist(action: NoteAssistAction) {
  if (!body.value.trim()) {
    toastStore.error('笔记正文为空，无法整理')
    return
  }
  assistBusy.value = action
  try {
    assist.value = await api.assistDraft({
      action,
      body: body.value,
      title: title.value.trim() || undefined,
    })
  } catch (reason) {
    toastStore.error(errText(reason, '整理失败'))
  } finally {
    assistBusy.value = null
  }
}

function applyAssist() {
  const result = assist.value
  if (!result) return
  if (result.action === 'tags') {
    const merged = new Set(tagList.value)
    for (const item of result.items) merged.add(item)
    tagsInput.value = Array.from(merged).join(', ')
    toastStore.success('标签已合并')
  } else if (result.action === 'tidy') {
    body.value = result.result
    toastStore.success('已应用格式整理')
  } else {
    const heading = result.action === 'summarize' ? '摘要' : '大纲'
    body.value = `${body.value.replace(/\s*$/, '')}\n\n## ${heading}\n\n${result.result}\n`
    toastStore.success('已插入到文末')
  }
  assist.value = null
}

// --- 导出 -------------------------------------------------------------------
const exporting = ref<NoteExportFormat | null>(null)

async function exportAs(format: NoteExportFormat) {
  const id = await ensureSaved()
  if (!id) return
  exporting.value = format
  try {
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
  refPick.value = ''
  refOptions.value = []
  try {
    if (refType.value === 'ENTRY') {
      const list: KnowledgeEntryView[] = await api.knowledgeEntries({})
      refOptions.value = list.map(item => ({ value: item.id, label: `${item.title}（${item.domain} · ${item.topicName}）` }))
    } else if (refType.value === 'FILE') {
      const list: FileView[] = await api.files()
      refOptions.value = list.map(item => ({ value: item.id, label: item.originalName }))
    } else {
      const list: InferenceView[] = await api.tasks()
      refOptions.value = list.map(item => ({ value: item.id, label: `${item.taskType} · ${item.traceId.slice(0, 8)}` }))
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

onMounted(load)
</script>

<template>
  <div class="page-stack note-editor-page">
    <section class="page-intro page-intro--split">
      <div>
        <p class="page-kicker">NOTE EDITOR</p>
        <h2>{{ isNew ? '新建笔记' : '编辑笔记' }}</h2>
        <p>左侧 Markdown 编辑，右侧实时预览。可引用文件、推理任务与知识卡，支持 AI 整理、导出与分享。</p>
      </div>
      <div class="intro-actions note-editor-head">
        <RouterLink :to="{ name: 'notes' }" class="button button--ghost">
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

    <p v-if="error" class="inline-alert inline-alert--error">{{ error }}</p>

    <section class="panel note-editor-meta">
      <div class="note-editor-fields">
        <input v-model="title" class="field-input note-title-input" maxlength="180" placeholder="无标题笔记" />
        <div class="note-editor-row">
          <label class="field-label note-status-field">
            状态
            <select v-model="status" class="field-input">
              <option v-for="option in statusOptions" :key="option.code" :value="option.code">{{ option.label }}</option>
            </select>
          </label>
          <label class="field-label note-tags-field">
            标签（逗号分隔）
            <input v-model="tagsInput" class="field-input" maxlength="500" placeholder="糖代谢, 酶动力学" />
          </label>
        </div>
      </div>
      <div class="note-editor-stats">
        <span><b>{{ wordCount }}</b> 字</span>
        <span>约 <b>{{ readMinutes }}</b> 分钟</span>
        <span v-if="updatedAt">更新于 {{ formatDate(updatedAt) }}</span>
        <span v-if="dirty" class="note-dirty">未保存</span>
      </div>
      <div class="note-editor-export">
        <span class="note-editor-export-label"><AppIcon name="export" :size="15" /> 导出</span>
        <button class="button button--ghost button--small" :disabled="exporting !== null" @click="exportAs('md')">Markdown</button>
        <button class="button button--ghost button--small" :disabled="exporting !== null" @click="exportAs('pdf')">PDF</button>
        <button class="button button--ghost button--small" :disabled="exporting !== null" @click="exportAs('docx')">DOCX</button>
      </div>
    </section>

    <section class="panel note-ai-bar">
      <div class="note-ai-head">
        <span class="note-ai-icon"><AppIcon name="ai" :size="18" /></span>
        <div>
          <strong>AI 整理</strong>
          <small>结果来源会如实标注：未配置模型密钥时使用本地规则，不冒充模型输出。</small>
        </div>
      </div>
      <div class="note-ai-actions">
        <button
          v-for="item in assistActions"
          :key="item.action"
          class="button button--ghost button--small"
          :disabled="assistBusy !== null"
          @click="runAssist(item.action)"
        >
          <AppIcon :name="item.icon" :size="15" />
          {{ assistBusy === item.action ? '整理中…' : item.label }}
        </button>
      </div>
    </section>

    <section v-if="assist" class="panel note-ai-result" :class="{ 'note-ai-result--local': assistIsLocal }">
      <header>
        <div>
          <p class="page-kicker">{{ assist.action.toUpperCase() }}</p>
          <strong>{{ assistEngineLabel }}</strong>
        </div>
        <div class="note-ai-result-actions">
          <button class="button button--small button--dark" @click="applyAssist">
            <AppIcon name="check" :size="14" /> 应用
          </button>
          <button class="icon-button" title="关闭" @click="assist = null"><AppIcon name="close" :size="17" /></button>
        </div>
      </header>
      <ul v-if="assist.action === 'tags' || assist.action === 'outline'" class="note-ai-items">
        <li v-for="(item, index) in assist.items" :key="index">{{ item }}</li>
      </ul>
      <pre v-else class="note-ai-text">{{ assist.result }}</pre>
      <p v-if="assist.note" class="note-ai-note">{{ assist.note }}</p>
    </section>

    <section class="note-editor-layout" :class="{ 'note-editor-layout--outline': outline.length > 0 }">
      <article class="panel note-editor-pane">
        <header class="note-pane-head">
          <strong>编辑</strong>
          <div class="note-md-tools">
            <button v-for="tool in tools" :key="tool.title" type="button" :title="tool.title" @click="tool.run">{{ tool.label }}</button>
          </div>
        </header>
        <textarea
          ref="bodyEl"
          v-model="body"
          class="note-editor-textarea"
          spellcheck="false"
          placeholder="开始记录。支持 Markdown：## 标题、- 列表、**加粗**、`代码`、> 引用、表格与代码块。"
          @keydown="onBodyKeydown"
        />
      </article>

      <article class="panel note-preview-pane">
        <header class="note-pane-head">
          <strong>预览</strong>
          <span class="note-preview-hint">内容已消毒后渲染</span>
        </header>
        <div v-if="previewHtml" class="markdown-body" v-html="previewHtml" />
        <p v-else class="note-preview-empty">预览会随左侧输入实时更新。</p>
      </article>

      <aside v-if="outline.length" class="panel note-outline-pane">
        <header class="note-pane-head"><strong>大纲</strong></header>
        <nav class="note-outline">
          <span v-for="item in outline" :key="item.anchor" :class="`note-outline--l${item.level}`">{{ item.text }}</span>
        </nav>
      </aside>
    </section>

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
