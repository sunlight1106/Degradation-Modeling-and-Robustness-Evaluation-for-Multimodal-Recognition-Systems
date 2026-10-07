<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type { NoteStatusCode, NoteSummaryView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'
import { noteLibraries } from '@/lib/noteLibraries'
import LoraColabCard from '@/components/LoraColabCard.vue'

const route = useRoute()
const notes = ref<NoteSummaryView[]>([])
const knownLibraries = ref<string[]>([...noteLibraries])
const loading = ref(true), error = ref(''), keyword = ref('')
const status = ref<NoteStatusCode | 'ALL'>('ALL')
const library = computed(() => typeof route.query.library === 'string' ? route.query.library : '')
const canWrite = computed(() => authStore.has('note:write'))
const tabs = [{ code: 'ALL', label: '全部' }, { code: 'DRAFT', label: '草稿' }, { code: 'ACTIVE', label: '已定稿' }, { code: 'ARCHIVED', label: '已归档' }] as const
const libraryNotes = computed(() => notes.value.filter(note => !library.value || (note.library || '综合学习') === library.value))
const filteredNotes = computed(() => libraryNotes.value.filter(note => status.value === 'ALL' || note.status.code === status.value))
const folded = ref(new Set<string>())
const treeRows = computed(() => {
  const result: Array<{ note: NoteSummaryView; depth: number }> = []
  const visible = new Map(filteredNotes.value.map(note => [note.id, note]))
  const visited = new Set<string>()
  const visit = (note: NoteSummaryView, depth: number) => {
    if (visited.has(note.id)) return
    visited.add(note.id); result.push({ note, depth: Math.min(depth, 8) })
    if (!folded.value.has(note.id) || keyword.value) for (const child of filteredNotes.value) if (child.parentId === note.id) visit(child, depth + 1)
  }
  for (const note of filteredNotes.value) if (!note.parentId || !visible.has(note.parentId)) visit(note, 0)
  return result
})
function togglePage(id: string) { const next = new Set(folded.value); next.has(id) ? next.delete(id) : next.add(id); folded.value = next }
const hasChildren = (id: string) => filteredNotes.value.some(note => note.parentId === id)
const libraries = computed(() => [...new Set([...knownLibraries.value, ...(library.value ? [library.value] : [])])])
const libraryCount = (name: string) => notes.value.filter(note => (note.library || '综合学习') === name).length
const tabCount = (code: string) => libraryNotes.value.filter(note => code === 'ALL' || note.status.code === code).length
let version = 0, timer: ReturnType<typeof setTimeout> | undefined
async function load() {
  const current = ++version
  loading.value = true; error.value = ''
  try {
    const result = await api.notes({ keyword: keyword.value.trim() || undefined })
    if (current !== version) return
    notes.value = result
    knownLibraries.value = [...new Set([...knownLibraries.value, ...result.map(note => note.library || '综合学习')])]
  } catch (reason) { if (current === version) error.value = reason instanceof ApiClientError ? reason.message : '笔记加载失败' }
  finally { if (current === version) loading.value = false }
}
watch(keyword, () => { version++; clearTimeout(timer); timer = setTimeout(load, 250) })
watch(library, () => { status.value = 'ALL' })
async function remove(note: NoteSummaryView) {
  if (!window.confirm(`删除笔记「${note.title}」？将移入回收站，可随时恢复。`)) return
  try { await api.deleteNote(note.id); toastStore.success('已移入回收站'); await load() }
  catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '删除失败') }
}
onMounted(load)
onBeforeUnmount(() => { version++; clearTimeout(timer) })
</script>

<template>
  <div class="page-stack learning-notes-page"><RouterLink to="/app/research?tab=trash" class="button button--ghost">打开回收站</RouterLink>
    <section class="page-intro page-intro--split">
      <div><p class="page-kicker">KNOWLEDGE / NOTEBOOKS</p><h2>{{ library || '我的学习库' }}</h2><p>把理解写下来，把知识连接起来。笔记仅自己可见，主动分享后他人才能阅读。</p></div>
      <div class="intro-actions">
        <RouterLink :to="{ name: 'knowledge' }" class="button button--ghost">知识目录 ↗</RouterLink>
        <RouterLink v-if="canWrite" :to="{ name: 'note-create', query: { library: library || '综合学习' } }" class="button button--dark"><AppIcon name="plus" :size="16" /> 新建笔记</RouterLink>
      </div>
    </section>
    <div class="learning-layout">
      <aside class="learning-sidebar">
        <p class="page-kicker">学习库列表</p>
        <RouterLink :to="{ name: 'notes' }" :class="{ selected: !library }"><span>全部笔记</span><small>{{ notes.length }}</small></RouterLink>
        <RouterLink v-for="name in libraries" :key="name" :to="{ name: 'notes', query: { library: name } }" :class="{ selected: library === name }"><span>{{ name }}</span><small>{{ libraryCount(name) }}</small></RouterLink>
        <p class="learning-aside-hint">新建笔记时可输入新的学习库名称。保存后会自动加入此列表。</p>
        <RouterLink v-if="library === '英语学习'" to="/app/vocabulary" class="learning-related"><span>词汇复习</span><span>↗</span></RouterLink>
      </aside>
      <section class="learning-list">
        <LoraColabCard v-if="library === '计算机学习'" />
        <div class="learning-toolbar">
          <nav aria-label="笔记状态"><button v-for="tab in tabs" :key="tab.code" :class="{ selected: status === tab.code }" :aria-pressed="status === tab.code" @click="status = tab.code">{{ tab.label }} <small>{{ tabCount(tab.code) }}</small></button></nav>
          <label class="learning-search"><AppIcon name="search" :size="16" /><input v-model="keyword" type="search" placeholder="搜索标题、正文、标签" aria-label="搜索笔记" /></label>
        </div>
        <p v-if="error" class="inline-alert inline-alert--error">{{ error }} <button class="button button--ghost" @click="load">重试</button></p>
        <div v-if="loading" class="table-skeleton" aria-label="正在加载笔记" />
        <template v-else-if="!filteredNotes.length">
          <EmptyState icon="note" :title="keyword ? '没有匹配的笔记' : '从一篇学习记录开始'" :description="keyword ? '试试其他关键词或切换学习库。' : '使用模板记录目标、例子、理解与复习问题。'" />
          <RouterLink v-if="canWrite && !keyword" class="button button--ghost" :to="{ name: 'note-create', query: { library: library || '综合学习', template: 'study' } }">用学习模板开始 →</RouterLink>
        </template>
        <article v-for="row in treeRows" v-else :key="row.note.id" class="learning-note-row" :style="{ paddingLeft: `${row.depth * 22 + 8}px` }">
          <div><span class="learning-note-meta">{{ row.note.library || '综合学习' }} <span> / </span> {{ row.note.status.label }} <span> / </span> {{ row.note.contentFormat === 'HTML' ? 'HTML' : 'Markdown' }}</span>
            <h3><button v-if="hasChildren(row.note.id)" class="page-tree-toggle" :aria-expanded="!folded.has(row.note.id)" :aria-label="folded.has(row.note.id) ? '展开子页面' : '收起子页面'" @click="togglePage(row.note.id)">{{ folded.has(row.note.id) ? "▸" : "▾" }}</button><RouterLink :to="{ name: 'note-edit', params: { id: row.note.id } }">{{ row.note.title }}</RouterLink></h3>
            <p>{{ row.note.excerpt || '暂无正文' }}</p>
            <div class="learning-note-tags"><button v-for="tag in row.note.tags" :key="tag" @click="keyword = tag">{{ tag }}</button></div>
          </div>
          <div class="learning-note-end"><RouterLink v-if="canWrite" :to="{ name: 'note-create', query: { library: row.note.library, parent: row.note.id } }" class="page-child-link">＋ 子页面</RouterLink><time>{{ new Date(row.note.updatedAt).toLocaleDateString('zh-CN') }}</time><button v-if="canWrite" class="icon-button" :aria-label="`删除 ${row.note.title}`" @click="remove(row.note)"><AppIcon name="trash" :size="15" /></button></div>
        </article>
        <footer class="learning-list-footer">{{ keyword ? '搜索结果' : '当前列表' }} · {{ filteredNotes.length }} 篇笔记 <span>Markdown / HTML 编写 · MD / PDF / Word / HTML / TXT 导出</span></footer>
      </section>
    </div>
  </div>
</template>
