import { createApp, nextTick, reactive } from 'vue'
import VocabularyBookShelf from '../components/VocabularyBookShelf.vue'

const fixture = document.getElementById('fixture'), passed = []
const assert = (value, message) => { if (!value) throw new Error(message); passed.push(message) }
const base = { description: 'Synthetic catalog entry', attribution: 'Synthetic source', owned: false, learned: 0, learning: 0, due: 0, mistakes: 0 }
const books = [
  { id: 'vocab-ec-ky', title: '考研英语综合词汇', level: '考研', totalWords: 4796 },
  { id: 'vocab-ec-ielts', title: '雅思 IELTS 词汇', level: '雅思', totalWords: 5026 },
  { id: 'vocab-ec-gk', title: '高中英语高考词汇', level: '高中', totalWords: 3666 },
  { id: 'private', title: '<script>我的词书</script>', level: '自定义', totalWords: 4, owned: true },
  ...Array.from({ length: 9 }, (_, i) => ({ id: `extra-${i}`, title: `练习 ${i}`, level: '四级', totalWords: 100 + i })),
].map(item => ({ ...base, ...item }))
const events = [], props = reactive({ books, selected: 'vocab-ec-ky', busy: false,
  onChoose: id => events.push(['choose', id]), onPreview: id => events.push(['preview', id]), onImport: () => events.push(['import']) })
let app
const button = text => [...fixture.querySelectorAll('button')].find(el => el.textContent.includes(text))
const click = async el => { el.click(); await nextTick() }
try {
  app = createApp(VocabularyBookShelf, props); app.mount(fixture); await nextTick()
  assert(fixture.querySelectorAll('.shelf-book').length === 9, 'Catalog initially renders a bounded nine books')
  assert(fixture.querySelector('.is-current h4').textContent.includes('考研'), 'Current study book is marked and pinned')
  assert(fixture.querySelector('.shelf-heading').textContent.includes('12 本内置词书'), 'Public count excludes private imports')
  await click(button('再看'))
  assert(fixture.querySelectorAll('.shelf-book').length === 13 && !fixture.querySelector('.shelf-more'), 'Show more reveals remaining books without duplicates')
  await click(button('留学考试'))
  assert(fixture.querySelectorAll('.shelf-book').length === 1 && fixture.querySelector('.shelf-book h4').textContent.includes('雅思'), 'Category filters IELTS independently of other exam books')
  const search = fixture.querySelector('input[type=search]')
  search.value = 'IELTS'; search.dispatchEvent(new window.Event('input', { bubbles: true })); await nextTick()
  assert(fixture.querySelectorAll('.shelf-book').length === 1, 'Search is case-insensitive and combines with the selected category')
  search.value = '雅思词汇'; search.dispatchEvent(new window.Event('input', { bubbles: true })); await nextTick()
  assert(fixture.querySelectorAll('.shelf-book').length === 1, 'Chinese exam aliases find titles containing an English acronym')
  search.value = '不存在的版本'; search.dispatchEvent(new window.Event('input', { bubbles: true })); await nextTick()
  assert(!!fixture.querySelector('.shelf-empty'), 'Unknown editions show a useful empty state instead of fabricated books')
  await click(button('清除筛选'))
  assert(fixture.querySelectorAll('.shelf-book').length === 9 && search.value === '', 'Clear restores catalog and resets the display limit')
  await click(button('中小学'))
  await click(button('查看单词'))
  assert(events.at(-1)?.[0] === 'preview' && events.at(-1)[1] === 'vocab-ec-gk', 'Word-list action carries the correct book identity')
  await click(button('选这本词书'))
  assert(events.at(-1)?.[0] === 'choose' && events.at(-1)[1] === 'vocab-ec-gk', 'Selection carries the correct book identity')
  await click(button('我的导入'))
  assert(fixture.querySelectorAll('.shelf-book').length === 1 && !fixture.querySelector('script'), 'Private titles render as escaped text')
  await click(button('导入自己的词书'))
  assert(events.at(-1)?.[0] === 'import', 'Private book import remains reachable')
  assert(fixture.querySelectorAll('.shelf-categories [aria-pressed=true]').length === 1, 'Exactly one accessible category is selected')
  document.getElementById('results').textContent = `PASS ${passed.length}\n${passed.join('\n')}`
  document.documentElement.dataset.result = 'passed'
} catch (error) {
  document.getElementById('results').textContent = `FAIL: ${error.stack}\n${passed.join('\n')}`
  document.documentElement.dataset.result = 'failed'
} finally { app?.unmount() }
