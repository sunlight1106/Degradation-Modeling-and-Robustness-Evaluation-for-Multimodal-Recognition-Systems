<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type { NoteStatusCode, NoteSummaryView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'

const router = useRouter()

const notes = ref<NoteSummaryView[]>([])
const loading = ref(true)
const error = ref('')
const keyword = ref('')
const status = ref<NoteStatusCode | 'ALL'>('ALL')

const canWrite = computed(() => authStore.has('note:write'))

const statusTabs: Array<{ code: NoteStatusCode | 'ALL'; label: string }> = [
  { code: 'ALL', label: '全部' },
  { code: 'DRAFT', label: '草稿' },
  { code: 'ACTIVE', label: '已定稿' },
  { code: 'ARCHIVED', label: '已归档' },
]

const allTags = computed(() => {
  const set = new Set<string>()
  for (const note of notes.value) for (const tag of note.tags) set.add(tag)
  return Array.from(set).slice(0, 20)
})

const counts = computed(() => {
  const base = { ALL: 0, DRAFT: 0, ACTIVE: 0, ARCHIVED: 0 } as Record<string, number>
  for (const note of notes.value) {
    base.ALL++
    base[note.status.code]++
  }
  return base
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    notes.value = await api.notes({
      status: status.value === 'ALL' ? undefined : status.value,
      keyword: keyword.value.trim() || undefined,
    })
  } catch (reason) {
    error.value = reason instanceof ApiClientError ? reason.message : '笔记加载失败'
  } finally {
    loading.value = false
  }
}

function switchStatus(next: NoteStatusCode | 'ALL') {
  status.value = next
  load()
}

function open(note: NoteSummaryView) {
  router.push({ name: 'note-edit', params: { id: note.id } })
}

function filterByTag(tag: string) {
  keyword.value = tag
  status.value = 'ALL'
}

let searchTimer: ReturnType<typeof setTimeout> | null = null
watch(keyword, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    status.value = 'ALL'
    load()
  }, 320)
})

async function remove(note: NoteSummaryView) {
  if (!window.confirm(`删除笔记「${note.title}」？此操作不可撤销。`)) return
  try {
    await api.deleteNote(note.id)
    toastStore.success('笔记已删除')
    await load()
  } catch (reason) {
    toastStore.error(reason instanceof ApiClientError ? reason.message : '删除失败')
  }
}

function formatDate(value: string) {
  const date = new Date(value)
  const diff = Date.now() - date.getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 604_800_000) return `${Math.floor(diff / 86_400_000)} 天前`
  return date.toLocaleDateString('zh-CN')
}

onMounted(load)
</script>

<template>
  <div class="page-stack notes-page">
    <section class="page-intro page-intro--split">
      <div>
        <p class="page-kicker">MY NOTES</p>
        <h2>我的笔记</h2>
        <p>Markdown 笔记，可引用上传的图片视频、推理任务与知识卡，支持 AI 整理、导出与平台内分享。</p>
      </div>
      <RouterLink v-if="canWrite" :to="{ name: 'note-create' }" class="button button--dark">
        <AppIcon name="plus" :size="17" /> 新建笔记
      </RouterLink>
    </section>

    <section class="notes-toolbar panel">
      <label class="kb-search notes-search">
        <AppIcon name="search" :size="16" />
        <input v-model="keyword" class="field-input" type="search" placeholder="搜索标题、正文或标签" />
      </label>
      <div class="notes-tabs">
        <button
          v-for="tab in statusTabs"
          :key="tab.code"
          :class="{ active: status === tab.code }"
          @click="switchStatus(tab.code)"
        >
          {{ tab.label }}<b>{{ counts[tab.code] ?? 0 }}</b>
        </button>
      </div>
      <div v-if="allTags.length" class="notes-tags">
        <i v-for="tag in allTags" :key="tag" @click="filterByTag(tag)">{{ tag }}</i>
      </div>
    </section>

    <p v-if="error" class="inline-alert inline-alert--error">{{ error }}</p>

    <section v-if="loading" class="notes-grid">
      <div v-for="n in 6" :key="n" class="note-card note-card--skeleton"><div class="table-skeleton" /></div>
    </section>

    <EmptyState
      v-else-if="!notes.length"
      icon="note"
      :title="keyword.trim() ? '没有匹配的笔记' : '还没有笔记'"
      :description="keyword.trim() ? '换个关键词，或新建一篇笔记。' : '从一篇笔记开始，把研究过程沉淀下来。'"
    />

    <section v-else class="notes-grid">
      <article v-for="note in notes" :key="note.id" class="note-card" @click="open(note)">
        <header>
          <span class="note-status" :class="`note-status--${note.status.code.toLowerCase()}`">
            {{ note.status.label }}
          </span>
          <time>{{ formatDate(note.updatedAt) }}</time>
        </header>
        <h3>{{ note.title }}</h3>
        <p class="note-excerpt">{{ note.excerpt || '（暂无正文）' }}</p>
        <footer>
          <span class="note-card-tags">
            <i v-for="tag in note.tags.slice(0, 3)" :key="tag" @click.stop="filterByTag(tag)">{{ tag }}</i>
          </span>
          <span class="note-card-meta">
            <template v-if="note.shareCount"><AppIcon name="external" :size="14" /> {{ note.shareCount }}</template>
            <button v-if="canWrite" class="note-delete" title="删除笔记" @click.stop="remove(note)">
              <AppIcon name="close" :size="14" />
            </button>
          </span>
        </footer>
      </article>
    </section>
  </div>
</template>
