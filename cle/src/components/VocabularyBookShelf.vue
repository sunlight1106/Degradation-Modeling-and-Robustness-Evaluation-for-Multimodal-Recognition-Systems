<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { VocabularyBook } from '@/api/vocabulary'
import AppIcon from './AppIcon.vue'

const props = defineProps<{ books: VocabularyBook[]; selected: string; busy: boolean }>()
const emit = defineEmits<{ choose: [id: string]; preview: [id: string]; import: [] }>()
const query = ref(''), category = ref('全部'), sort = ref('recommended'), limit = ref(9)
const categories = ['全部', '考研', '四六级', '留学考试', '中小学', '专业与通用', '我的导入']
const aliases: Record<string, string> = { 四级: 'CET4 CET-4', 六级: 'CET6 CET-6', 考研: '英语一 英语二', 雅思: 'IELTS', 托福: 'TOEFL', GRE: '研究生入学考试' }
function group(book: VocabularyBook) {
  if (book.owned) return '我的导入'
  if (book.level === '考研') return '考研'
  if (['四级', '六级'].includes(book.level)) return '四六级'
  if (['雅思', '托福', 'GRE'].includes(book.level)) return '留学考试'
  if (['小学衔接', '初中', '高中'].includes(book.level)) return '中小学'
  return '专业与通用'
}
const publicBooks = computed(() => props.books.filter(book => !book.owned))
const totalEntries = computed(() => publicBooks.value.reduce((sum, book) => sum + book.totalWords, 0))
const filtered = computed(() => {
  const terms = query.value.trim().toLowerCase().split(/\s+/).filter(Boolean)
  const rows = props.books.filter(book => (category.value === '全部' || group(book) === category.value)
    && terms.every(term => `${book.title} ${book.level}词汇 ${book.description} ${aliases[book.level] || ''}`.toLowerCase().includes(term)))
  return rows.sort((a, b) => sort.value === 'words' ? a.totalWords - b.totalWords
    : sort.value === 'progress' ? b.learned / Math.max(b.totalWords, 1) - a.learned / Math.max(a.totalWords, 1)
    : Number(b.id === props.selected) - Number(a.id === props.selected)
      || Number(b.id.startsWith('vocab-ec-')) - Number(a.id.startsWith('vocab-ec-')))
})
const visible = computed(() => filtered.value.slice(0, limit.value))
watch([query, category, sort], () => { limit.value = 9 })
function clear() { query.value = ''; category.value = '全部' }
</script>

<template>
  <div class="book-shelf">
    <header class="shelf-heading"><div><p class="shelf-eyebrow">FIND YOUR NEXT WORDS</p><h3>从你的目标，找到一本词书。</h3><p>{{ publicBooks.length }} 本内置词书 · {{ totalEntries.toLocaleString() }} 个词条收录 · 学习进度独立保存</p></div><button class="button button--light" :disabled="busy" @click="emit('import')"><AppIcon name="plus" :size="16" />导入自己的词书</button></header>
    <div class="shelf-tools"><label class="shelf-search"><AppIcon name="search" :size="17" /><input v-model="query" type="search" maxlength="80" placeholder="搜索考研、雅思、高中、计算机…" aria-label="搜索词书" /></label><label class="shelf-sort">排序<select v-model="sort" aria-label="词书排序"><option value="recommended">推荐顺序</option><option value="words">词量从少到多</option><option value="progress">学习进度</option></select></label></div>
    <nav class="shelf-categories" aria-label="词书分类"><button v-for="item in categories" :key="item" :aria-pressed="category === item" @click="category = item">{{ item }}<small>{{ item === '全部' ? books.length : books.filter(book => group(book) === item).length }}</small></button></nav>
    <div class="shelf-result"><span role="status">{{ category }} · {{ filtered.length }} 本词书</span><span>同一单词可能收录于不同词书</span></div>
    <div v-if="visible.length" class="shelf-grid">
      <article v-for="book in visible" :key="book.id" class="shelf-book" :class="{ 'is-current': selected === book.id }">
        <div class="shelf-book-top"><span>{{ book.owned ? '私人词书' : book.level }}</span><span v-if="selected === book.id" class="shelf-current"><AppIcon name="check" :size="13" />正在学习</span><small v-else>{{ book.totalWords.toLocaleString() }} 词</small></div>
        <h4>{{ book.title }}</h4><p class="shelf-description">{{ book.description }}</p>
        <div class="shelf-progress-copy"><span>{{ book.learned.toLocaleString() }} / {{ book.totalWords.toLocaleString() }} 词掌握</span><span>{{ book.due }} 待复习</span></div>
        <div class="shelf-progress" role="progressbar" :aria-label="book.title + '学习进度'" :aria-valuenow="book.learned" :aria-valuemax="book.totalWords" aria-valuemin="0"><i :style="{ width: `${book.totalWords ? book.learned / book.totalWords * 100 : 0}%` }" /></div>
        <div class="shelf-actions"><button :disabled="busy" @click="emit('choose', book.id)">{{ selected === book.id ? '继续学习' : '选这本词书' }} <span aria-hidden="true">→</span></button><button :disabled="busy" @click="emit('preview', book.id)">查看单词</button></div>
        <details class="shelf-source"><summary>词书来源</summary><p>{{ book.attribution }}</p></details>
      </article>
    </div>
    <div v-else class="shelf-empty"><AppIcon name="book" :size="28" /><h4>还没有匹配的词书</h4><p>换一个关键词，或导入自己整理的版本。</p><button class="button button--light" @click="clear">清除筛选</button><button class="button button--light" :disabled="busy" @click="emit('import')">导入词书</button></div>
    <button v-if="visible.length < filtered.length" class="shelf-more" @click="limit += 9">再看 {{ Math.min(9, filtered.length - visible.length) }} 本词书 <span aria-hidden="true">↓</span></button>
    <details class="shelf-editions"><summary>寻找指定年份或教材版本？</summary><p>内置词书按考试方向与主题整理。闪过 2028、红宝书 2028、人教版等具体版本，可通过「导入自己的词书」添加你有权使用的内容；内置词书不冒充这些出版物的原版或完整考纲。</p></details>
  </div>
</template>

<style scoped>
.book-shelf{--shelf-accent:var(--green,#416d82)}
.shelf-heading{display:flex;justify-content:space-between;align-items:center;gap:24px;margin-bottom:28px}.shelf-heading h3{font-size:26px;letter-spacing:-.5px;margin:8px 0 12px}.shelf-heading p{font-size:12px;color:var(--muted);line-height:1.8}.shelf-heading .shelf-eyebrow{font:10px ui-monospace,monospace;letter-spacing:1.5px}.shelf-heading .button{flex-shrink:0}
.shelf-tools{display:flex;gap:30px;align-items:center;border-block:1px solid var(--line);padding:15px 0}.shelf-search{display:flex;gap:12px;align-items:center;flex:1;color:var(--muted)}.shelf-search input{width:100%;background:transparent;border:0;padding:9px 0;color:var(--ink);font:inherit;font-size:14px;outline-offset:5px}.shelf-sort{display:flex;gap:12px;align-items:center;font-size:12px;color:var(--muted)}.shelf-sort select{background:var(--paper);border:1px solid var(--line);padding:9px;color:var(--ink);font:inherit}
.shelf-categories{display:flex;gap:7px;flex-wrap:wrap;margin:19px 0 22px}.shelf-categories button{background:transparent;border:0;border-bottom:2px solid transparent;color:var(--muted);padding:10px 12px;cursor:pointer;font:inherit;font-size:13px;transition:color .15s,background .15s}.shelf-categories button:hover{color:var(--ink);background:var(--hover-paper)}.shelf-categories button[aria-pressed=true]{border-color:var(--shelf-accent);color:var(--shelf-accent)}.shelf-categories small{font:10px ui-monospace,monospace;margin-left:9px;opacity:.7}.shelf-result{display:flex;justify-content:space-between;color:var(--muted);font-size:11px;margin:0 0 14px}.shelf-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:20px}
.shelf-book{border:1px solid var(--line);padding:23px;display:flex;flex-direction:column;min-width:0;transition:border-color .15s,background .15s}.shelf-book:hover{border-color:var(--shelf-accent);background:var(--hover-paper)}.shelf-book.is-current{border-color:var(--shelf-accent);box-shadow:inset 3px 0 var(--shelf-accent)}.shelf-book-top{display:flex;justify-content:space-between;align-items:center;gap:10px;font-size:11px;color:var(--muted)}.shelf-current{display:flex;gap:5px;align-items:center;color:var(--shelf-accent)}.shelf-book h4{font-size:20px;font-weight:600;margin:19px 0 12px;line-height:1.5;overflow-wrap:anywhere}.shelf-description{font-size:12px;color:var(--muted);line-height:1.85;margin:0 0 22px;flex:1}.shelf-progress-copy{display:flex;justify-content:space-between;gap:10px;color:var(--muted);font-size:10px}.shelf-progress{height:3px;background:var(--line);margin:10px 0 17px}.shelf-progress i{height:100%;display:block;background:var(--shelf-accent)}.shelf-actions{display:flex;justify-content:space-between;gap:12px}.shelf-actions button{background:none;border:0;padding:5px 0;color:var(--shelf-accent);font:inherit;font-size:12px;cursor:pointer}.shelf-actions button:hover{text-decoration:underline}.shelf-actions button:first-child{font-weight:600}.shelf-actions button span{display:inline-block;margin-left:8px;transition:transform .15s}.shelf-actions button:hover span{transform:translateX(3px)}.shelf-source{margin-top:13px;font-size:10px;color:var(--muted)}.shelf-source summary{cursor:pointer}.shelf-source p{margin:8px 0 0;line-height:1.6;overflow-wrap:anywhere}.shelf-more{display:block;background:none;border:1px solid var(--line);width:100%;padding:15px;margin-top:22px;cursor:pointer;color:var(--shelf-accent);font:inherit;font-size:13px}.shelf-more:hover{border-color:var(--shelf-accent)}.shelf-more span{margin-left:15px}.shelf-editions{margin-top:26px;color:var(--muted);font-size:12px;line-height:1.9}.shelf-editions summary{cursor:pointer}.shelf-empty{text-align:center;border-block:1px solid var(--line);padding:45px}.shelf-empty h4{font-size:18px}.shelf-empty p{font-size:13px;color:var(--muted)}.shelf-empty .button{margin:12px 5px 0}.book-shelf button:focus-visible,.book-shelf summary:focus-visible{outline:2px solid var(--shelf-accent);outline-offset:3px}@media(max-width:1050px){.shelf-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:720px){.shelf-grid{grid-template-columns:1fr}.shelf-heading{align-items:flex-start;flex-direction:column}.shelf-tools{gap:10px;flex-wrap:wrap}.shelf-result>span:last-child{display:none}}
</style>
