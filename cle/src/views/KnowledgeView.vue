<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, ApiClientError } from '@/api/client'
import type { KnowledgeEntryView, KnowledgeTopicView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'
import { renderMarkdown, markdownToText, countWords, readingMinutes } from '@/lib/markdown'

const topics = ref<KnowledgeTopicView[]>([])
const entries = ref<KnowledgeEntryView[]>([])
const activeTopicId = ref<number | null>(null)
const activeEntry = ref<KnowledgeEntryView | null>(null)
const keyword = ref('')
const loading = ref(true)
const error = ref('')
const busy = ref(false)

const canWrite = computed(() => authStore.has('knowledge:write'))

const domains = computed(() => {
  const map = new Map<string, KnowledgeTopicView[]>()
  for (const topic of topics.value) {
    const list = map.get(topic.domain) ?? []
    list.push(topic)
    map.set(topic.domain, list)
  }
  return Array.from(map.entries()).map(([domain, items]) => ({ domain, items }))
})

const totalEntries = computed(() => topics.value.reduce((sum, item) => sum + item.entryCount, 0))
const entryHtml = computed(() => renderMarkdown(activeEntry.value?.body))

async function load() {
  loading.value = true
  error.value = ''
  try {
    topics.value = await api.knowledgeTopics()
    await loadEntries()
  } catch (reason) {
    error.value = reason instanceof ApiClientError ? reason.message : '知识库加载失败'
  } finally {
    loading.value = false
  }
}

async function loadEntries() {
  try {
    entries.value = await api.knowledgeEntries({
      topicId: keyword.value.trim() ? undefined : activeTopicId.value ?? undefined,
      keyword: keyword.value.trim() || undefined,
    })
  } catch (reason) {
    error.value = reason instanceof ApiClientError ? reason.message : '知识卡加载失败'
  }
}

async function selectTopic(id: number | null) {
  activeTopicId.value = id
  activeEntry.value = null
  keyword.value = ''
  await loadEntries()
}

async function openEntry(item: KnowledgeEntryView) {
  try {
    activeEntry.value = await api.knowledgeEntry(item.id)
  } catch (reason) {
    toastStore.error(reason instanceof ApiClientError ? reason.message : '知识卡打开失败')
  }
}

let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(keyword, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    if (keyword.value.trim()) activeTopicId.value = null
    loadEntries()
  }, 320)
})

// --- 新建主题 ---------------------------------------------------------------
const topicModal = ref(false)
const topicForm = ref({ domain: '', name: '', description: '' })

function openTopicModal() {
  topicForm.value = { domain: domains.value[0]?.domain ?? '', name: '', description: '' }
  topicModal.value = true
}

async function submitTopic() {
  if (!topicForm.value.domain.trim() || !topicForm.value.name.trim()) return
  busy.value = true
  try {
    await api.createKnowledgeTopic({
      domain: topicForm.value.domain.trim(),
      name: topicForm.value.name.trim(),
      description: topicForm.value.description.trim() || undefined,
    })
    topicModal.value = false
    toastStore.success('主题已创建')
    await load()
  } catch (reason) {
    toastStore.error(reason instanceof ApiClientError ? reason.message : '创建失败')
  } finally {
    busy.value = false
  }
}

async function removeTopic(topic: KnowledgeTopicView) {
  if (!window.confirm(`删除主题「${topic.name}」？主题下若仍有知识卡将无法删除。`)) return
  try {
    await api.deleteKnowledgeTopic(topic.id)
    if (activeTopicId.value === topic.id) await selectTopic(null)
    toastStore.success('主题已删除')
    await load()
  } catch (reason) {
    toastStore.error(reason instanceof ApiClientError ? reason.message : '删除失败')
  }
}

// --- 新建知识卡 -------------------------------------------------------------
const entryModal = ref(false)
const entryForm = ref({ topicId: 0, title: '', summary: '', body: '', tags: '' })

function openEntryModal(source?: KnowledgeEntryView) {
  entryForm.value = source
    ? { topicId: source.topicId, title: '', summary: '', body: source.body, tags: source.tags.join(', ') }
    : { topicId: activeTopicId.value ?? topics.value[0]?.id ?? 0, title: '', summary: '', body: '', tags: '' }
  entryModal.value = true
}

async function submitEntry() {
  if (!entryForm.value.topicId || !entryForm.value.title.trim() || !entryForm.value.body.trim()) return
  busy.value = true
  try {
    const saved = await api.createKnowledgeEntry({
      topicId: entryForm.value.topicId,
      title: entryForm.value.title.trim(),
      summary: entryForm.value.summary.trim() || undefined,
      body: entryForm.value.body,
      tags: entryForm.value.tags.trim() || undefined,
    })
    entryModal.value = false
    toastStore.success('知识卡已创建')
    await load()
    activeEntry.value = saved
  } catch (reason) {
    toastStore.error(reason instanceof ApiClientError ? reason.message : '创建失败')
  } finally {
    busy.value = false
  }
}

async function removeEntry(item: KnowledgeEntryView) {
  if (!window.confirm(`删除知识卡「${item.title}」？此操作不可撤销。`)) return
  try {
    await api.deleteKnowledgeEntry(item.id)
    if (activeEntry.value?.id === item.id) activeEntry.value = null
    toastStore.success('知识卡已删除')
    await load()
  } catch (reason) {
    toastStore.error(reason instanceof ApiClientError ? reason.message : '删除失败')
  }
}

function filterByTag(tag: string) {
  keyword.value = tag
  activeEntry.value = null
}

onMounted(load)
</script>

<template>
  <div class="page-stack knowledge-page">
    <section class="page-intro page-intro--split">
      <div>
        <p class="page-kicker">KNOWLEDGE BASE</p>
        <h2>知识库</h2>
        <p>跨学科知识沉淀。预置生物化学与医学框架，可自由新增计算机、数学、物理等任意领域。</p>
      </div>
      <div class="intro-actions">
        <button v-if="canWrite" class="button" @click="openTopicModal">
          <AppIcon name="plus" :size="16" /> 新建主题
        </button>
        <button v-if="canWrite" class="button button--dark" :disabled="!topics.length" @click="openEntryModal()">
          <AppIcon name="plus" :size="16" /> 新建知识卡
        </button>
      </div>
    </section>

    <section class="kb-stats">
      <div><small>学科领域</small><strong>{{ domains.length }}</strong></div>
      <div><small>知识主题</small><strong>{{ topics.length }}</strong></div>
      <div><small>知识卡</small><strong>{{ totalEntries }}</strong></div>
      <div><small>当前筛选</small><strong>{{ entries.length }}</strong></div>
    </section>

    <section class="kb-layout panel">
      <aside class="kb-sidebar">
        <label class="kb-search">
          <AppIcon name="search" :size="16" />
          <input v-model="keyword" class="field-input" type="search" placeholder="搜索标题、正文或标签" />
        </label>

        <button
          class="kb-topic kb-topic--all"
          :class="{ active: activeTopicId === null && !keyword.trim() }"
          @click="selectTopic(null)"
        >
          <AppIcon name="book" :size="17" /><span>全部知识卡</span><b>{{ totalEntries }}</b>
        </button>

        <div v-if="loading" class="table-skeleton" />
        <nav v-else class="kb-domain-list">
          <section v-for="group in domains" :key="group.domain" class="kb-domain">
            <header>{{ group.domain }}</header>
            <button
              v-for="topic in group.items"
              :key="topic.id"
              class="kb-topic"
              :class="{ active: activeTopicId === topic.id }"
              @click="selectTopic(topic.id)"
            >
              <span>{{ topic.name }}</span>
              <b>{{ topic.entryCount }}</b>
              <i v-if="topic.builtin" class="kb-builtin" title="平台预置主题" />
              <i
                v-if="canWrite && !topic.builtin"
                class="kb-topic-delete"
                title="删除主题"
                @click.stop="removeTopic(topic)"
              ><AppIcon name="close" :size="13" /></i>
            </button>
          </section>
        </nav>
        <p v-if="error" class="inline-alert inline-alert--error">{{ error }}</p>
      </aside>

      <div class="kb-entries">
        <EmptyState
          v-if="!loading && !entries.length"
          icon="book"
          :title="keyword.trim() ? '没有匹配的知识卡' : '该主题下还没有知识卡'"
          :description="keyword.trim() ? '换个关键词试试，或直接创建一张新卡。' : '点击右上角「新建知识卡」开始积累。'"
        />
        <article
          v-for="item in entries"
          v-else
          :key="item.id"
          class="kb-card"
          :class="{ active: activeEntry?.id === item.id }"
          @click="openEntry(item)"
        >
          <header>
            <span class="kb-card-domain">{{ item.domain }} · {{ item.topicName }}</span>
            <i v-if="item.builtin" class="kb-card-builtin">预置</i>
          </header>
          <h3>{{ item.title }}</h3>
          <p v-if="item.summary">{{ item.summary }}</p>
          <p v-else class="kb-card-excerpt">{{ markdownToText(item.body, 120) }}</p>
          <footer>
            <span class="kb-card-tags">
              <i v-for="tag in item.tags.slice(0, 4)" :key="tag">{{ tag }}</i>
              <i v-if="item.tags.length > 4" class="kb-card-more">+{{ item.tags.length - 4 }}</i>
            </span>
            <span class="kb-card-meta">
              <b>{{ countWords(item.body) }}</b> 字 · <b>{{ readingMinutes(item.body) }}</b> 分钟
              <template v-if="item.noteReferences"> · <b>{{ item.noteReferences }}</b> 篇引用</template>
            </span>
          </footer>
        </article>
      </div>

      <aside v-if="activeEntry" class="kb-reader">
        <header>
          <div>
            <p class="page-kicker">{{ activeEntry.domain }} · {{ activeEntry.topicName }}</p>
            <h3>{{ activeEntry.title }}</h3>
          </div>
          <div class="kb-reader-actions">
            <button v-if="canWrite" class="icon-button" title="以本卡内容为模板新建" @click="openEntryModal(activeEntry)">
              <AppIcon name="copy" :size="17" />
            </button>
            <button
              v-if="canWrite && !activeEntry.builtin"
              class="icon-button"
              title="删除知识卡"
              @click="removeEntry(activeEntry)"
            ><AppIcon name="close" :size="17" /></button>
            <button class="icon-button" title="关闭" @click="activeEntry = null">
              <AppIcon name="chevron" :size="17" />
            </button>
          </div>
        </header>
        <p v-if="activeEntry.summary" class="kb-reader-summary">{{ activeEntry.summary }}</p>
        <div class="kb-reader-meta">
          <span><b>{{ countWords(activeEntry.body) }}</b> 字</span>
          <span>约 <b>{{ readingMinutes(activeEntry.body) }}</b> 分钟读完</span>
          <span>更新于 {{ new Date(activeEntry.updatedAt).toLocaleDateString('zh-CN') }}</span>
          <span v-if="activeEntry.noteReferences"><b>{{ activeEntry.noteReferences }}</b> 篇笔记引用</span>
        </div>
        <div class="markdown-body" v-html="entryHtml" />
        <footer v-if="activeEntry.tags.length" class="kb-reader-tags">
          <i v-for="tag in activeEntry.tags" :key="tag" @click="filterByTag(tag)">{{ tag }}</i>
        </footer>
      </aside>
    </section>

    <div v-if="topicModal" class="modal-backdrop" @click.self="topicModal = false">
      <form class="modal-card" @submit.prevent="submitTopic">
        <header>
          <div><p class="page-kicker">NEW TOPIC</p><h3>新建知识主题</h3></div>
          <button type="button" class="icon-button" @click="topicModal = false"><AppIcon name="close" /></button>
        </header>
        <label class="field-label">
          学科领域
          <input v-model="topicForm.domain" class="field-input" list="kb-domains" maxlength="40" required placeholder="如：生物化学与医学、计算机科学" />
          <datalist id="kb-domains">
            <option v-for="group in domains" :key="group.domain" :value="group.domain" />
          </datalist>
        </label>
        <label class="field-label">主题名称<input v-model="topicForm.name" class="field-input" maxlength="120" required placeholder="如：糖代谢" /></label>
        <label class="field-label">主题描述<input v-model="topicForm.description" class="field-input" maxlength="500" placeholder="一句话说明该主题涵盖的内容" /></label>
        <button class="button button--dark button--full" :disabled="busy">{{ busy ? '创建中…' : '创建主题' }}</button>
      </form>
    </div>

    <div v-if="entryModal" class="modal-backdrop" @click.self="entryModal = false">
      <form class="modal-card modal-card--wide" @submit.prevent="submitEntry">
        <header>
          <div><p class="page-kicker">NEW ENTRY</p><h3>新建知识卡</h3></div>
          <button type="button" class="icon-button" @click="entryModal = false"><AppIcon name="close" /></button>
        </header>
        <div class="form-grid">
          <label class="field-label">
            所属主题
            <select v-model.number="entryForm.topicId" class="field-input" required>
              <option v-for="topic in topics" :key="topic.id" :value="topic.id">{{ topic.domain }} · {{ topic.name }}</option>
            </select>
          </label>
          <label class="field-label">标签（逗号分隔）<input v-model="entryForm.tags" class="field-input" maxlength="500" placeholder="糖酵解, PFK-1, 代谢调控" /></label>
        </div>
        <label class="field-label">标题<input v-model="entryForm.title" class="field-input" maxlength="180" required /></label>
        <label class="field-label">摘要<input v-model="entryForm.summary" class="field-input" maxlength="500" placeholder="一句话概括，显示在卡片上" /></label>
        <label class="field-label">
          正文（Markdown）
          <textarea v-model="entryForm.body" class="field-input kb-editor-textarea" rows="14" required placeholder="## 小节标题&#10;&#10;支持表格、列表、代码块。" />
        </label>
        <p class="field-hint">{{ countWords(entryForm.body) }} 字 · 内容经消毒后渲染，可放心粘贴外部资料</p>
        <button class="button button--dark button--full" :disabled="busy">{{ busy ? '创建中…' : '创建知识卡' }}</button>
      </form>
    </div>
  </div>
</template>
