<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { noteTree } from '@/lib/noteTree'
import NoteBatchTools from '@/components/NoteBatchTools.vue'
import NoteTemplateGallery from '@/components/NoteTemplateGallery.vue'
import NoteReviewList from '@/components/NoteReviewList.vue'
import { api, ApiClientError } from '@/api/client'
import type { NoteStatusCode, NoteSummaryView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'
import { noteLibraries } from '@/lib/noteLibraries'
import LoraColabCard from '@/components/LoraColabCard.vue'

const route = useRoute(), router = useRouter()
const selectedIds = ref(new Set<string>()), page = ref(0), sort = ref('updated'), batchBusy = ref(false)
const selectedNotes = computed(() => notes.value.filter(n => selectedIds.value.has(n.id)))
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
const deleting = ref(new Set<string>())
const arranged = computed(() => [...filteredNotes.value].sort((a,b) => sort.value === 'title' ? a.title.localeCompare(b.title,'zh-CN') : sort.value === 'oldest' ? Date.parse(a.updatedAt)-Date.parse(b.updatedAt) : Date.parse(b.updatedAt)-Date.parse(a.updatedAt)))
const tree = computed(() => noteTree(arranged.value,folded.value,!!keyword.value))
const treeRows = computed(() => tree.value.rows.slice(page.value*50,(page.value+1)*50))
const pages = computed(() => Math.max(1,Math.ceil(tree.value.rows.length/50)))
function toggleSelected(id:string) { const next=new Set(selectedIds.value);if(next.has(id))next.delete(id);else if(next.size<50)next.add(id);else toastStore.error('一次最多选择 50 篇笔记');selectedIds.value=next }
function selectPage(){selectedIds.value=new Set(treeRows.value.map(r=>r.note.id))}
function clearSelection(){selectedIds.value=new Set()}
function chooseTemplate(id:string,target:string){void router.push({name:'note-create',query:{library:library.value||target,template:id}})}
async function organized(count:number){clearSelection();toastStore.success(`已整理 ${count} 篇笔记`);await load()}
watch([status,library,sort],()=>{page.value=0;clearSelection()})
function togglePage(id: string) { const next = new Set(folded.value); next.has(id) ? next.delete(id) : next.add(id); folded.value = next }
const hasChildren = (id: string) => tree.value.parents.has(id)
const libraries = computed(() => [...new Set([...knownLibraries.value, ...(library.value ? [library.value] : [])])])
const libraryCount = (name: string) => notes.value.filter(note => (note.library || '综合学习') === name).length
const tabCount = (code: string) => libraryNotes.value.filter(note => code === 'ALL' || note.status.code === code).length
let active = true, version = 0, timer: ReturnType<typeof setTimeout> | undefined
async function load() {
  const current = ++version
  loading.value = true; error.value = ''
  try {
    const result = await api.notes({ keyword: keyword.value.trim() || undefined })
    if (!active || current !== version) return
    notes.value = result; page.value = 0; clearSelection()
    knownLibraries.value = [...new Set([...knownLibraries.value, ...result.map(note => note.library || '综合学习')])]
  } catch (reason) { if (active && current === version) { notes.value = []; clearSelection(); error.value = reason instanceof ApiClientError ? reason.message : '笔记加载失败' } }
  finally { if (active && current === version) loading.value = false }
}
watch(keyword, () => { version++; clearTimeout(timer); timer = setTimeout(load, 250) })
watch(library, () => { status.value = 'ALL' })
async function remove(note: NoteSummaryView) {
  if (deleting.value.has(note.id) || !active) return
  if (!window.confirm(`删除笔记「${note.title}」？将移入回收站，可随时恢复。`)) return
  deleting.value = new Set([...deleting.value, note.id])
  try { await api.deleteNote(note.id); if (active) { toastStore.success('已移入回收站'); await load() } }
  catch (reason) { if (active) toastStore.error(reason instanceof ApiClientError ? reason.message : '删除失败') }
  finally { if (active) { const remaining = new Set(deleting.value); remaining.delete(note.id); deleting.value = remaining } }
}
onMounted(load)
onBeforeUnmount(() => { active = false; version++; clearTimeout(timer) })
</script>

<template>
  <div class="page-stack learning-notes-page"><RouterLink to="/app/research?tab=trash" class="button button--ghost">打开回收站</RouterLink>
    <section class="page-intro page-intro--split">
      <div><p class="page-kicker">KNOWLEDGE / NOTEBOOKS</p><h2>{{ library || '我的学习库' }}</h2><p>把理解写下来，把知识连接起来。笔记仅自己可见，主动分享后他人才能阅读。</p></div>
      <div class="intro-actions"><NoteTemplateGallery v-if="canWrite" @choose="chooseTemplate" />
        <RouterLink :to="{ name: 'knowledge' }" class="button button--ghost">知识目录 ↗</RouterLink>
        <RouterLink v-if="canWrite" :to="{ name: 'note-create', query: { library: library || '综合学习' } }" class="button button--dark"><AppIcon name="plus" :size="16" /> 新建笔记</RouterLink>
      </div>
    </section>
    <NoteReviewList />
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
        <div class="note-list-options"><label>排列方式<select v-model="sort" class="field-input"><option value="updated">最近更新</option><option value="oldest">最早更新</option><option value="title">标题</option></select></label><button v-if="canWrite && treeRows.length" class="table-action" :disabled="batchBusy" @click="selectPage">选择当前页</button><span>每页最多 50 篇</span></div><NoteBatchTools :selected="selectedNotes" @clear="clearSelection" @busy="batchBusy=$event" @changed="organized" />
        <p v-if="error" class="inline-alert inline-alert--error">{{ error }} <button class="button button--ghost" @click="load">重试</button></p>
        <div v-if="loading" class="table-skeleton" aria-label="正在加载笔记" />
        <template v-else-if="!filteredNotes.length">
          <EmptyState icon="note" :title="keyword ? '没有匹配的笔记' : '从一篇学习记录开始'" :description="keyword ? '试试其他关键词或切换学习库。' : '使用模板记录目标、例子、理解与复习问题。'" />
          <RouterLink v-if="canWrite && !keyword" class="button button--ghost" :to="{ name: 'note-create', query: { library: library || '综合学习', template: 'study' } }">用学习模板开始 →</RouterLink>
        </template>
        <article v-for="row in treeRows" v-else :key="row.note.id" class="learning-note-row" :style="{ paddingLeft: `${row.depth * 22 + 8}px` }">
          <div><label v-if="canWrite" class="note-row-selection"><input type="checkbox" :checked="selectedIds.has(row.note.id)" :disabled="batchBusy" :aria-label="`选择 ${row.note.title}`" @change="toggleSelected(row.note.id)" /></label><span class="learning-note-meta">{{ row.note.library || '综合学习' }} <span> / </span> {{ row.note.status.label }} <span> / </span> {{ row.note.contentFormat === 'HTML' ? 'HTML' : 'Markdown' }}</span>
            <h3><button v-if="hasChildren(row.note.id)" class="page-tree-toggle" :aria-expanded="!folded.has(row.note.id)" :aria-label="folded.has(row.note.id) ? '展开子页面' : '收起子页面'" @click="togglePage(row.note.id)">{{ folded.has(row.note.id) ? "▸" : "▾" }}</button><RouterLink :to="{ name: 'note-edit', params: { id: row.note.id } }">{{ row.note.title }}</RouterLink></h3>
            <p>{{ row.note.excerpt || '暂无正文' }}</p>
            <div class="learning-note-tags"><button v-for="tag in row.note.tags" :key="tag" @click="keyword = tag">{{ tag }}</button></div>
          </div>
          <div class="learning-note-end"><RouterLink v-if="canWrite" :to="{ name: 'note-create', query: { library: row.note.library, parent: row.note.id } }" class="page-child-link">＋ 子页面</RouterLink><time>{{ new Date(row.note.updatedAt).toLocaleDateString('zh-CN') }}</time><button v-if="canWrite" class="icon-button" :aria-label="`删除 ${row.note.title}`" :disabled="deleting.has(row.note.id)" @click="remove(row.note)"><AppIcon name="trash" :size="15" /></button></div>
        </article>
        <nav v-if="pages>1" class="note-pagination" aria-label="笔记分页"><button class="button button--ghost" :disabled="page===0||batchBusy" @click="page--">上一页</button><span>{{ page+1 }} / {{ pages }}</span><button class="button button--ghost" :disabled="page+1>=pages||batchBusy" @click="page++">下一页</button></nav>
        <footer class="learning-list-footer">{{ keyword ? '搜索结果' : '当前列表' }} · {{ filteredNotes.length }} 篇笔记 <span>Markdown / HTML 编写 · MD / PDF / Word / HTML / TXT 导出</span></footer>
      </section>
    </div>
  </div>
</template>

<style scoped>.note-list-options{display:flex;align-items:center;gap:18px;padding:12px 0;font-size:12px;color:var(--muted)}.note-list-options label{display:flex;align-items:center;gap:8px}.note-list-options select{width:130px;padding:8px}.note-row-selection{margin-right:12px}.note-pagination{display:flex;gap:18px;align-items:center;justify-content:flex-end;margin:20px 0;font-size:12px}</style>
