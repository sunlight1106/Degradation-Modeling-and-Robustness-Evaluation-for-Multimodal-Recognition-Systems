<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ApiClientError } from '@/api/client'
import { authStore } from '@/stores/auth'
import VocabularyAssistant from '@/components/VocabularyAssistant.vue'
import WordPronunciation from '@/components/WordPronunciation.vue'
import type { VocabularyLesson } from '@/api/vocabulary'
import { vocabularyApi } from '@/api/vocabulary'
import type { VocabularyAnswer, VocabularyDashboard, VocabularyImport, VocabularyMode, VocabularyQuestion, VocabularyWordPage } from '@/api/vocabulary'
import AppIcon from '@/components/AppIcon.vue'
import VocabularyBookShelf from '@/components/VocabularyBookShelf.vue'
import VocabularyRecallCard from '@/components/VocabularyRecallCard.vue'
import VocabularyLessonCard from '@/components/VocabularyLessonCard.vue'

const dashboard = ref<VocabularyDashboard | null>(null)
const assistantLesson = ref<VocabularyLesson | null>(null)
const selected = ref(''), tab = ref<'study' | 'books' | 'words' | 'stats'>('study')
const mode = ref<VocabularyMode>('LEARN'), question = ref<VocabularyQuestion | null>(null), answer = ref<VocabularyAnswer | null>(null)
const studyCard = ref<HTMLElement | null>(null), continueButton = ref<HTMLButtonElement | null>(null)
const settingsDialog = ref<HTMLDialogElement | null>(null)
const selectedOption = ref(''), emptyReason = ref(''), error = ref(''), busy = ref(''), loading = ref(true)
const sessionCount = ref(0), sessionCorrect = ref(0), sessionLearned = ref(0), showSettings = ref(false), showImport = ref(false)
const settings = reactive({ zoneId: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Etc/UTC', dailyGoal: 10 })
const wordPage = ref<VocabularyWordPage | null>(null), wordFilter = ref('ALL'), wordQuery = ref(''), page = ref(0)
const importText = ref(''), rightsConfirmed = ref(false)
const studyStyle = ref('RECALL'), showBackup = ref(false), backupFile = ref<File | null>(null), restoreConfirmed = ref(false), notice = ref('')
const commonZones = ['Asia/Shanghai', 'Asia/Hong_Kong', 'Asia/Tokyo', 'Asia/Singapore', 'Europe/London', 'Europe/Paris', 'America/New_York', 'America/Los_Angeles', 'Australia/Sydney', 'Etc/UTC']
const selectedBook = computed(() => dashboard.value?.books.find(book => book.id === selected.value))
const accuracy = computed(() => dashboard.value?.today.answers ? Math.round(dashboard.value.today.correct / dashboard.value.today.answers * 100) : 0)
const goalProgress = computed(() => Math.min(100, (dashboard.value?.today.learned || 0) / (dashboard.value?.settings.dailyGoal || 10) * 100))
const maxAnswers = computed(() => Math.max(1, ...dashboard.value?.history.map(day => day.answers) || []))
const modeNames: Record<VocabularyMode, string> = { LEARN: '学习新词', REVIEW: '到期复习', MISTAKES: '错词巩固' }
const filters = [{ id: 'ALL', label: '全部' }, { id: 'LEARNING', label: '学习中' }, { id: 'LEARNED', label: '初步掌握' }, { id: 'DUE', label: '到期' }, { id: 'MISTAKES', label: '错词' }, { id: 'STARRED', label: '收藏' }, { id: 'SKIPPED', label: '已跳过' }]
let active = true, generation = 0
watch(() => showSettings.value && !loading.value, async open => {
  if (!open) return
  await nextTick()
  if (!active || !showSettings.value || !settingsDialog.value) return
  if (typeof settingsDialog.value.showModal === 'function') { if (!settingsDialog.value.open) settingsDialog.value.showModal() }
  else settingsDialog.value.setAttribute('open', '')
})
function closeSettings() { if (!busy.value) showSettings.value = false }
function message(reason: unknown) { return reason instanceof ApiClientError ? reason.message : reason instanceof Error ? reason.message : '请求失败，请重试' }
async function run(name: string, fn: () => Promise<void>) {
  if (busy.value) return
  busy.value = name; error.value = ''
  try { await fn() } catch (reason) { if (active) error.value = message(reason) }
  finally { if (active) busy.value = '' }
}
async function refresh() {
  const version = ++generation, value = await vocabularyApi.dashboard()
  if (!active || version !== generation) return
  dashboard.value = value
  if (!selected.value || !value.books.some(book => book.id === selected.value)) selected.value = value.settings.selectedBookId || value.books.find(book => book.id === 'vocab-context')?.id || value.books[0]?.id || ''
  settings.zoneId = value.settings.zoneId || settings.zoneId; settings.dailyGoal = value.settings.dailyGoal
}
async function saveSettings() {
  await run('settings', async () => {
    await vocabularyApi.settings({ ...settings, selectedBookId: selected.value || null })
    if (!active) return
    showSettings.value = false; await refresh()
  })
}
async function chooseBook(id: string) {
  await run('book', async () => {
    if (dashboard.value?.settings.zoneId) await vocabularyApi.settings({ zoneId: dashboard.value.settings.zoneId, dailyGoal: dashboard.value.settings.dailyGoal, selectedBookId: id })
    if (!active) return
    selected.value = id; resetSession(); page.value = 0; wordPage.value = null; tab.value = 'study'
  })
}
async function previewBook(id: string) {
  await chooseBook(id)
  if (active && !error.value) { wordFilter.value = 'ALL'; wordQuery.value = ''; await selectTab('words') }
}
function resetSession() { question.value = null; answer.value = null; emptyReason.value = ''; selectedOption.value = ''; sessionCount.value = 0; sessionCorrect.value = 0; sessionLearned.value = 0 }
async function loadQuestion() {
  const value = await vocabularyApi.next(selected.value, mode.value, studyStyle.value)
  if (!active) return
  question.value = value.question; answer.value = null; selectedOption.value = ''; emptyReason.value = value.reason || ''
  await nextTick()
  if (active) studyCard.value?.focus()
}
async function start(nextMode: VocabularyMode) {
  await run('question', async () => {
    if (!dashboard.value?.settings.zoneId) {
      await vocabularyApi.settings({ zoneId: settings.zoneId, dailyGoal: settings.dailyGoal, selectedBookId: selected.value || null })
      if (!active) return
      await refresh()
    }
    if (!active) return
    resetSession(); mode.value = nextMode; tab.value = 'study'; await loadQuestion()
  })
}
async function next() { await run('question', loadQuestion) }
async function submit(optionId: string) {
  if (!question.value || answer.value) return
  const id = question.value.id
  await run('answer', async () => {
    selectedOption.value = optionId
    const result = await vocabularyApi.answer(id, optionId)
    if (!active || question.value?.id !== id) return
    answer.value = result; sessionCount.value++; sessionCorrect.value += Number(result.correct); sessionLearned.value += Number(result.newlyLearned)
    await refresh()
  })
  // Native buttons cannot receive focus until the busy state has been rendered away.
  await nextTick()
  if (active && !busy.value && question.value?.id === id && answer.value) continueButton.value?.focus()
}
async function submitRecall(text: string) {
  if (!question.value || answer.value) return
  const id = question.value.id
  await run('answer', async () => {
    const result = await vocabularyApi.recall(id, text)
    if (!active || question.value?.id !== id) return
    answer.value = result; sessionCount.value++; sessionCorrect.value += Number(result.correct); sessionLearned.value += Number(result.newlyLearned)
    await refresh()
  })
}
async function skipQuestion() {
  if (!question.value || answer.value) return
  await run('skip', async () => { await vocabularyApi.skipQuestion(question.value!.id); if (!active) return; await refresh(); await loadQuestion() })
}
async function skipWord(id: string, skipped: boolean) {
  await run('skip', async () => { await vocabularyApi.skip(id, skipped); if (!active) return; await refresh(); await loadWords() })
}
async function downloadBackup() { await run('backup', async () => { await vocabularyApi.backup(); if (active) notice.value = '备份已下载。请把文件另存到安全位置。' }) }
function selectBackup(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  restoreConfirmed.value = false
  if (file && file.size > 20 * 1024 * 1024) { error.value = '备份文件不能超过 20 MB'; backupFile.value = null; return }
  backupFile.value = file || null
}
async function restoreBackup() {
  if (!backupFile.value || !restoreConfirmed.value) return
  await run('restore', async () => { await vocabularyApi.restore(backupFile.value!); if (!active) return; resetSession(); wordPage.value = null; backupFile.value = null; restoreConfirmed.value = false; await refresh(); notice.value = '恢复完成。同词进度已合并，重复恢复不会重复计数。' })
}
async function loadWords() {
  const value = await vocabularyApi.words(selected.value, wordFilter.value, wordQuery.value, page.value)
  if (active) wordPage.value = value
}
async function selectTab(nextTab: typeof tab.value) {
  if (busy.value) return
  tab.value = nextTab
  if (nextTab === 'words' && selected.value) await run('words', loadWords)
}
async function searchWords(reset = true) { if (reset) page.value = 0; await run('words', loadWords) }
async function paginate(delta: number) { page.value += delta; await searchWords(false) }
async function star(id: string, starred: boolean) {
  await run('star', async () => {
    await vocabularyApi.star(id, starred)
    if (!active) return
    if (answer.value?.wordId === id) answer.value.starred = starred
    const entry = wordPage.value?.items.find(item => item.id === id); if (entry) entry.starred = starred
    await refresh()
  })
}
function template(): VocabularyImport {
  const words = [
    ['apple', '/ˈæpəl/', '苹果', 'I put an apple in my bag.', '我把一个苹果放进包里。'],
    ['window', '/ˈwɪndoʊ/', '窗户', 'Please open the window.', '请打开窗户。'],
    ['kitchen', '/ˈkɪtʃən/', '厨房', 'Our kitchen has a small table.', '我们的厨房里有一张小桌子。'],
    ['umbrella', '/ʌmˈbrelə/', '雨伞', 'Take an umbrella with you.', '带上一把雨伞。'],
  ]
  return { title: '我的第一本词书', description: '四词原创示例，可替换为你有权使用的词条。', attribution: '本人原创或已获授权', rightsConfirmed: false, words: words.map(([term, ipa, meaning, example, exampleTranslation]) => ({ term: term!, ipa: ipa!, pos: 'n.', meaning: meaning!, example: example!, exampleTranslation: exampleTranslation!, distractors: words.filter(item => item[0] !== term).map(item => item[2]!) })) }
}
function fillTemplate() { importText.value = JSON.stringify(template(), null, 2); rightsConfirmed.value = false }
async function readFile(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  if (!file) return
  if (file.size > 2 * 1024 * 1024) { error.value = '导入文件不能超过 2 MB'; input.value = ''; return }
  try { const text = await file.text(); if (active) { importText.value = text; rightsConfirmed.value = false } }
  catch { if (active) error.value = '文件读取失败，请重试或粘贴 JSON 内容' }
  finally { input.value = '' }
}
async function importBook() {
  await run('import', async () => {
    if (!rightsConfirmed.value) throw new Error('请先确认词库内容的使用权')
    if (new TextEncoder().encode(importText.value).length > 2 * 1024 * 1024) throw new Error('导入内容不能超过 2 MB')
    let payload: VocabularyImport
    try { payload = JSON.parse(importText.value) as VocabularyImport } catch { throw new Error('JSON 格式不正确，请检查示例格式') }
    if (!payload || !Array.isArray(payload.words)) throw new Error('JSON 必须包含 words 数组')
    const book = await vocabularyApi.import({ ...payload, rightsConfirmed: true })
    if (!active) return
    showImport.value = false; importText.value = ''; rightsConfirmed.value = false; selected.value = book.id; resetSession(); await refresh(); tab.value = 'study'
  })
}
function onKey(event: KeyboardEvent) {
  const target = event.target instanceof Element ? event.target : null
  if (event.defaultPrevented || event.repeat || event.isComposing || event.ctrlKey || event.metaKey || event.altKey || target?.closest('input, textarea, select, button, a[href], summary, [contenteditable]:not([contenteditable="false"])') || busy.value || assistantLesson.value || showImport.value || showSettings.value || showBackup.value || tab.value !== 'study') return
  if (/^[1-4]$/.test(event.key) && question.value && !answer.value && (!question.value.practiceKind || question.value.practiceKind === 'CHOICE')) { const option = question.value.options[Number(event.key) - 1]; if (option) { event.preventDefault(); void submit(option.id) } }
  if (event.key === 'Enter' && answer.value) { event.preventDefault(); void next() }
}
onMounted(async () => { window.addEventListener('keydown', onKey); try { await refresh() } catch (reason) { error.value = message(reason) } finally { loading.value = false } })
onBeforeUnmount(() => { active = false; generation++; window.removeEventListener('keydown', onKey) })
</script>

<template>
  <div class="page-stack vocabulary-page">
    <section class="page-intro page-intro--split">
      <div><p class="page-kicker">WORDS, EVERY DAY</p><h2>把单词，慢慢记住。</h2><p>用场景理解，用搭配串联，再遮住答案回忆。同一个词，在每本词书里接着学。</p></div>
      <div class="vocab-toolbar"><button class="button button--light" :disabled="!!busy" @click="showBackup = !showBackup">备份与恢复</button><button class="button button--light" :disabled="!!busy" @click="showSettings = !showSettings"><AppIcon name="settings" :size="16" />学习设置</button></div>
    </section>
    <p v-if="error" role="alert" class="inline-alert inline-alert--error">{{ error }} <button v-if="!dashboard" class="button button--light" @click="run('refresh', refresh)">重新加载</button><button v-else-if="question && !answer" class="button button--light" :disabled="!!busy" @click="next">恢复当前题目</button></p>
    <section v-if="loading" class="panel vocab-loading" aria-live="polite">正在准备你的词书与学习记录…</section>
    <template v-else-if="dashboard">
      <p v-if="notice" class="inline-alert" role="status">{{ notice }}</p>
      <section v-if="showBackup" class="panel vocab-settings">
        <h3>备份你的单词与进度</h3><p>下载当前账号的私有词书、收藏、跳过状态、回忆次数和复习日期。备份包含私人内容，请妥善保存。</p>
        <div class="vocab-import-tools"><button class="button button--dark" :disabled="!!busy" @click="downloadBackup">下载学习备份</button></div>
        <form @submit.prevent="restoreBackup"><label>选择备份文件<input type="file" accept=".json,.gz,application/gzip,application/json" :disabled="!!busy" @change="selectBackup" /></label><label class="vocab-rights"><input v-model="restoreConfirmed" type="checkbox" :disabled="!!busy" />我确认将此备份合并到当前账号；相同单词共享进度，不删除现有词书。</label><button class="button button--light" :disabled="!!busy || !backupFile || !restoreConfirmed">合并恢复</button></form>
      </section>
      <dialog v-if="showSettings" ref="settingsDialog" class="vocab-settings vocab-settings-dialog" aria-labelledby="vocab-settings-title" @cancel.prevent="closeSettings">
        <div><h3 id="vocab-settings-title">你的学习节奏</h3><p>按所选时区的日历日期安排复习。学会的词从当地次日开始到期，不是固定等待 24 小时。</p></div>
        <form @submit.prevent="saveSettings">
          <label>每日初步掌握目标<input v-model.number="settings.dailyGoal" class="field-input" type="number" min="1" max="100" required :disabled="!!busy" /></label>
          <label>学习时区<input v-model="settings.zoneId" class="field-input" list="vocab-timezones" placeholder="Asia/Shanghai" required maxlength="80" :disabled="!!busy" /><datalist id="vocab-timezones"><option v-for="zone in commonZones" :key="zone" :value="zone" /></datalist></label>
          <button class="button button--dark" :disabled="!!busy">{{ busy === 'settings' ? '正在准备…' : '保存设置' }}</button>
          <button type="button" class="button button--light" :disabled="!!busy" @click="closeSettings">取消</button>
        </form>
      </dialog>
      <section v-if="tab === 'study' || tab === 'stats'" class="vocab-metrics" aria-label="今日学习统计">
        <article class="panel"><span>今日初步掌握</span><strong>{{ dashboard.today.learned }}<small> / {{ dashboard.settings.dailyGoal }} 词</small></strong><div class="vocab-progress"><i :style="{ width: `${goalProgress}%` }" /></div></article>
        <article class="panel"><span>等待复习</span><strong>{{ dashboard.dueTotal }}<small> 词</small></strong><small>全部词书的到期单词</small></article>
        <article class="panel"><span>今日答题正确率</span><strong>{{ accuracy }}<small> %</small></strong><small>{{ dashboard.today.correct }} / {{ dashboard.today.answers }} 次答对</small></article>
        <article class="panel"><span>连续学习</span><strong>{{ dashboard.streak }}<small> 天</small></strong><small>完成任意一次答题即可记一天</small></article>
      </section>
      <nav class="vocab-tabs" aria-label="背单词功能"><button v-for="item in [{ id: 'study', text: '今日学习' }, { id: 'books', text: '我的词书' }, { id: 'words', text: '单词本' }, { id: 'stats', text: '学习记录' }]" :key="item.id" :class="{ active: tab === item.id }" :aria-current="tab === item.id ? 'page' : undefined" :disabled="!!busy" @click="selectTab(item.id as typeof tab)">{{ item.text }}</button></nav>
      <section v-if="tab === 'study'" class="vocab-study-layout">
        <aside class="panel vocab-book-summary">
          <p class="page-kicker">CURRENT BOOK</p><h3>{{ selectedBook?.title || '先选一本词书' }}</h3><p>{{ selectedBook?.description }}</p>
          <div class="vocab-progress"><i :style="{ width: `${selectedBook?.totalWords ? selectedBook.learned / selectedBook.totalWords * 100 : 0}%` }" /></div>
          <small>{{ selectedBook?.learned || 0 }} / {{ selectedBook?.totalWords || 0 }} 词初步掌握</small>
          <button class="button button--light" :disabled="!!busy" @click="selectTab('books')">更换词书</button><button v-if="selected !== 'vocab-context' && dashboard.books.some(book => book.id === 'vocab-context')" class="button button--light" :disabled="!!busy" @click="chooseBook('vocab-context')">每日场景课 · 86 词</button>
          <label class="vocab-small">练习方式<select v-model="studyStyle" class="field-input" :disabled="!!busy" @change="resetSession"><option value="RECALL">每日带练 · 拼写与搭配</option><option value="CHOICE">释义识记 · 四选一</option></select></label><small v-if="selectedBook?.shared">已接续 {{ selectedBook.shared }} 个同词的学习进度</small><small v-if="selectedBook?.skipped">{{ selectedBook.skipped }} 个熟词已跳过</small>
          <hr />
          <button class="button" :class="mode === 'LEARN' ? 'button--dark' : 'button--light'" :disabled="!!busy || !selected" @click="start('LEARN')">学习新词 <AppIcon name="arrow" :size="16" /></button>
          <button class="button" :class="mode === 'REVIEW' ? 'button--dark' : 'button--light'" :disabled="!!busy || !selected" @click="start('REVIEW')">到期复习 <span>{{ selectedBook?.due || 0 }}</span></button>
          <button class="button" :class="mode === 'MISTAKES' ? 'button--dark' : 'button--light'" :disabled="!!busy || !selected" @click="start('MISTAKES')">错词巩固 <span>{{ selectedBook?.mistakes || 0 }}</span></button>
          <p class="vocab-small">切换模式会替换未答题目；已完成的答题实时保存在你的账号中。</p>
        </aside>
        <article ref="studyCard" class="panel vocab-study-card" tabindex="-1" :aria-labelledby="question ? 'vocab-question-title' : 'vocab-welcome-title'" :aria-busy="!!busy" aria-live="polite" aria-atomic="false">
          <VocabularyRecallCard v-if="question && question.practiceKind && question.practiceKind !== 'CHOICE'" :question="question" :answer="answer" :busy="!!busy" @submit="submitRecall" @skip="skipQuestion" @next="next" /><button v-if="answer?.lesson && question?.practiceKind && question.practiceKind !== 'CHOICE' && authStore.has('personal-ai:use')" class="button button--light" @click="assistantLesson = answer.lesson">AI 学习助手</button>
          <template v-if="question && (!question.practiceKind || question.practiceKind === 'CHOICE')">
            <WordPronunciation :text="question.term" /><header class="vocab-card-top"><span>{{ modeNames[mode] }}</span><button v-if="!answer" class="button button--light" :disabled="!!busy" @click="skipQuestion">Skip · 我已熟悉</button><span>本轮 {{ sessionCount }} 题 · 答对 {{ sessionCorrect }}</span></header>
            <div class="vocab-word"><h3 id="vocab-question-title">{{ question.term }}</h3><p>{{ question.ipa }} <span>{{ question.pos }}</span></p><div class="vocab-recall" :aria-label="`累计答对 ${answer?.learningCorrect ?? question.learningCorrect} 次，目标 4 次`"><i v-for="n in 4" :key="n" :class="{ filled: n <= (answer?.learningCorrect ?? question.learningCorrect) }" /><span>{{ answer?.learningCorrect ?? question.learningCorrect }}/4 次正确回忆</span></div></div>
            <p class="vocab-prompt">选择这个词在本词条中的意思</p>
            <div class="vocab-options"><button v-for="(option, index) in question.options" :key="option.id" :disabled="!!busy || !!answer" :class="{ correct: answer?.correctOptionId === option.id, incorrect: answer && selectedOption === option.id && !answer.correct, selected: !answer && selectedOption === option.id }" @click="submit(option.id)"><span>{{ index + 1 }}</span>{{ option.meaning }}<AppIcon v-if="answer?.correctOptionId === option.id" name="check" :size="18" /></button></div>
            <section v-if="answer" class="vocab-feedback" :class="{ 'vocab-feedback--wrong': !answer.correct }">
              <strong>{{ answer.correct ? '记住这一次。' : '再认识它一次。' }} {{ answer.meaning }}</strong><p>{{ answer.message }}</p><VocabularyLessonCard v-if="answer.lesson" :lesson="answer.lesson" hide-identity /><p v-else-if="answer.example" class="vocab-example">{{ answer.example }}</p><p v-if="answer.exampleTranslation">{{ answer.exampleTranslation }}</p>
              <div class="vocab-feedback-actions"><button v-if="answer.lesson && authStore.has('personal-ai:use')" class="button button--light" @click="assistantLesson = answer.lesson">AI 学习助手</button><span v-if="answer.dueDate">下次复习：{{ answer.dueDate }}</span><button class="button button--light" :disabled="!!busy" @click="star(answer.wordId, !answer.starred)">{{ answer.starred ? '取消收藏' : '收藏这个词' }}</button></div>
            </section>
            <footer class="vocab-card-bottom"><small>{{ answer ? '按 Enter 继续' : '支持键盘 1–4 选择 · 每次选择即提交' }}</small><button v-if="answer" ref="continueButton" class="button button--dark" :disabled="!!busy" @click="next">{{ busy === 'question' ? '准备中…' : '下一个单词' }} <AppIcon name="arrow" :size="16" /></button></footer>
          </template>
          <template v-if="!question">
            <div class="vocab-welcome"><span class="vocab-welcome-icon"><AppIcon :name="emptyReason ? 'check' : 'book'" :size="34" /></span><p class="page-kicker">{{ emptyReason ? 'A LITTLE BETTER, EVERY DAY' : 'ONE WORD AT A TIME' }}</p><h3 id="vocab-welcome-title">{{ emptyReason || '今天，也进步一点。' }}</h3><p v-if="sessionCount">本轮完成 {{ sessionCount }} 题，答对 {{ sessionCorrect }} 次，新增初步掌握 {{ sessionLearned }} 词。</p><p v-else>先看一个画面，连着记完整搭配，再遮住英文回忆。答错保留原进度；熟词可以 Skip，换词书接着学。</p><button class="button button--dark" :disabled="!!busy || !selected" @click="start(emptyReason && mode === 'LEARN' ? 'REVIEW' : 'LEARN')">{{ emptyReason && mode === 'LEARN' ? '看看今日复习' : '开始学习' }} <AppIcon name="arrow" :size="16" /></button></div>
          </template>
        </article>
      </section>
      <section v-else-if="tab === 'books'" class="vocab-library">
        <section v-if="showImport" class="panel vocab-import" aria-labelledby="vocab-import-title"><h3 id="vocab-import-title">导入私有词书</h3><p>JSON 格式，每本 4–500 词，最多 20 本。每词需有音标、词性、一个明确释义、例句与译文，以及 3–8 个不与正确答案重叠的错误释义。导入内容仅自己可见。</p><div class="vocab-import-tools"><button class="button button--light" :disabled="!!busy" @click="fillTemplate">填入原创示例</button><label class="button button--light">读取 JSON 文件<input type="file" accept="application/json,.json" :disabled="!!busy" @change="readFile" /></label></div><label for="vocab-import-json">词书 JSON（最大 2 MB）</label><textarea id="vocab-import-json" v-model="importText" class="field-input vocab-json" spellcheck="false" :disabled="!!busy" placeholder="点“填入原创示例”查看完整格式" /><label class="vocab-rights"><input v-model="rightsConfirmed" type="checkbox" :disabled="!!busy" />我确认这些释义、音标、例句和干扰项由我原创或已获授权；不从付费词书中擅自复制。</label><div class="vocab-import-tools"><button class="button button--dark" :disabled="!!busy || !rightsConfirmed || !importText" @click="importBook">{{ busy === 'import' ? '正在导入…' : '确认导入' }}</button><button class="button button--light" :disabled="!!busy" @click="showImport = false">取消</button></div></section>
        <VocabularyBookShelf :books="dashboard.books" :selected="selected" :busy="!!busy" @choose="chooseBook" @preview="previewBook" @import="showImport = !showImport" />
      </section>
      <section v-else-if="tab === 'words'" class="panel vocab-word-list"><header><div><h3>{{ selectedBook?.title }} · 单词本</h3><p>查看释义不会增加记忆次数。错词巩固答对会清除错词标记，但不提前推进复习。</p></div><button class="button button--light" :disabled="!!busy" @click="selectTab('books')">切换词书</button></header><form class="vocab-word-controls" @submit.prevent="searchWords()"><label class="vocab-search"><AppIcon name="search" :size="16" /><input v-model="wordQuery" class="field-input" type="search" maxlength="80" placeholder="搜索单词或中文释义" :disabled="!!busy" /></label><select v-model="wordFilter" class="field-input" :disabled="!!busy" @change="searchWords()"><option v-for="filter in filters" :key="filter.id" :value="filter.id">{{ filter.label }}</option></select><button class="button button--light" :disabled="!!busy">搜索</button></form><p v-if="busy === 'words'" role="status">正在加载单词…</p><p v-else-if="!wordPage?.items.length" class="vocab-empty">当前筛选下没有单词。</p><article v-for="word in wordPage?.items" :key="word.id" class="vocab-word-row"><div><h4>{{ word.term }} <span>{{ word.ipa }} {{ word.pos }}</span></h4><p>{{ word.meaning }}</p><details v-if="word.lesson" class="vocab-word-detail"><summary>场景、搭配与用法</summary><VocabularyLessonCard :lesson="word.lesson" /></details><button v-if="word.lesson && authStore.has('personal-ai:use')" class="table-action" @click="assistantLesson = word.lesson">AI 讲解与练习</button><small v-if="word.example">{{ word.example }}<template v-if="word.exampleTranslation"> · {{ word.exampleTranslation }}</template></small></div><div class="vocab-word-row-meta"><span>{{ word.learningCorrect }}/4 次 · 错 {{ word.wrongCount }} 次</span><small>{{ word.dueDate ? `复习 ${word.dueDate}` : '尚未进入复习' }}</small><button class="button button--light" :disabled="!!busy" @click="skipWord(word.id, !word.skipped)">{{ word.skipped ? '恢复学习' : 'Skip' }}</button><button class="button button--light" :disabled="!!busy" @click="star(word.id, !word.starred)">{{ word.starred ? '已收藏' : '收藏' }}</button></div></article><footer class="vocab-pagination"><span>共 {{ wordPage?.total || 0 }} 词 · 第 {{ page + 1 }} 页</span><button class="button button--light" :disabled="!!busy || page === 0" @click="paginate(-1)">上一页</button><button class="button button--light" :disabled="!!busy || (page + 1) * 30 >= (wordPage?.total || 0)" @click="paginate(1)">下一页</button></footer></section>
      <section v-else class="panel vocab-stats"><header><div><h3>每一次回忆，都算数。</h3><p>最近 14 天的答题记录 · {{ dashboard.settings.zoneId || '请先设置学习时区' }}</p></div></header><div class="vocab-history" aria-label="最近14天学习记录"><div v-for="day in dashboard.history" :key="day.date || ''" class="vocab-history-day"><span>{{ day.answers }}</span><div><i :style="{ height: `${Math.max(3, day.answers / maxAnswers * 100)}%` }" :class="{ empty: !day.answers }" /></div><small>{{ day.date?.slice(5) }}</small></div></div><div class="vocab-history-table"><div v-for="day in [...dashboard.history].reverse()" :key="day.date || ''"><span>{{ day.date }}</span><span>{{ day.answers }} 题 / {{ day.correct }} 对</span><span>掌握 {{ day.learned }} 词</span><span>复习 {{ day.reviews }} 次</span></div></div></section>
      <details class="panel vocab-how"><summary>学习规则与内容说明</summary><ul><li>每日带练先看场景和完整搭配，再遮住英文回忆。即时、提示后、延迟独立回忆分别记录；带练中查看答案后至少间隔 60 秒，无提示答对才增加掌握次数。释义识记累计四次正确选择。初步掌握不代表永久记住。</li><li>新掌握单词从学习时区的次日开始复习。每次到期答对后，间隔依次为 3、7、14、30、60 天；之后保持 60 天。</li><li>Skip 只标记熟词跳过，不计为答题掌握。在单词本筛选“已跳过”即可恢复原进度。</li><li>复习答错会回到次日复习，并从最短复习间隔重新开始。错词巩固不增加学习次数、不改变复习日期。</li><li>初步掌握目标按全部词书合计。学习中的小批次会轮换出题；题目有效期 30 分钟，重复提交只计一次。</li><li>原有 3 本入门词书保留原创例句；新增词书采用 ECDICT 释义与音标，未提供的例句不虚构补齐。练习只覆盖显示的释义。</li><li>内置词书可离线使用，按考试标签、词频或主题选编，来源可在每本词书内展开查看；同一账号里相同单词跨词书共享进度；导入时忽略大小写与首尾空白并去重，不合并不同词形。</li></ul></details>
    </template>
  </div>
<VocabularyAssistant v-if="assistantLesson" :key="assistantLesson.term" :lesson="assistantLesson" @close="assistantLesson = null" /></template>

<style scoped>
.vocab-settings-dialog{position:fixed;inset:0;margin:auto;width:min(650px,calc(100vw - 48px));max-height:85vh;overflow:auto;background:var(--paper);color:var(--ink);border:1px solid var(--line);border-radius:10px;padding:30px;box-shadow:0 24px 70px #0003}.vocab-settings-dialog::backdrop{background:#15242c70}.vocab-settings-dialog form{gap:18px}.vocab-settings-dialog label{max-width:none;flex:1;min-width:180px}
.vocab-toolbar{display:flex;gap:10px}.vocab-word-detail{margin:12px 0;font-size:12px;max-width:600px}.vocab-word-detail summary{cursor:pointer;color:var(--green)}
.vocab-study-card:focus-visible{outline:3px solid var(--vocab-green);outline-offset:3px}
.vocabulary-page{--vocab-green:var(--green);--vocab-soft:color-mix(in srgb,var(--vocab-green) 9%,var(--paper,#fff))}.vocab-loading,.vocab-empty{padding:48px;text-align:center}.vocab-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px}.vocab-metrics article{padding:22px;display:grid;gap:12px}.vocab-metrics article>span,.vocab-metrics small,.vocab-small{color:var(--muted,#737772);font-size:12px}.vocab-metrics strong{font-size:34px;font-weight:600;letter-spacing:-1px}.vocab-metrics strong small{font-size:13px;font-weight:400;letter-spacing:0}.vocab-progress{height:5px;background:var(--line,#e8eae7);border-radius:8px;overflow:hidden}.vocab-progress i{display:block;height:100%;background:var(--vocab-green);transition:width .2s}.vocab-tabs{display:flex;gap:8px;border-bottom:1px solid var(--line,#ddd)}.vocab-tabs button{padding:14px 20px;border:0;border-bottom:2px solid transparent;background:none;color:var(--muted,#777);font:inherit;cursor:pointer}.vocab-tabs button.active{border-color:var(--vocab-green);color:var(--ink,#222);font-weight:600}.vocab-study-layout{display:grid;grid-template-columns:270px minmax(0,1fr);gap:22px;align-items:start}.vocab-book-summary{padding:25px;display:flex;flex-direction:column;gap:17px}.vocab-book-summary h3,.vocab-library h3,.vocab-word-list h3,.vocab-settings h3,.vocab-stats h3{font-size:20px;margin:0}.vocab-book-summary p,.vocab-library p,.vocab-word-list header p,.vocab-settings p,.vocab-stats p{line-height:1.75;font-size:13px;color:var(--muted,#777);margin:0}.vocab-book-summary .page-kicker{font-size:10px;letter-spacing:2px}.vocab-book-summary .button{justify-content:space-between}.vocab-book-summary hr{width:100%;border:0;border-top:1px solid var(--line,#eee);margin:0}.vocab-book-summary>small{font-size:12px;color:var(--muted,#777)}.vocab-study-card{padding:30px;min-height:570px}.vocab-card-top,.vocab-card-bottom{display:flex;align-items:center;justify-content:space-between;gap:12px;color:var(--muted,#777);font-size:12px}.vocab-card-top>span:first-child{color:var(--vocab-green);background:var(--vocab-soft);padding:6px 10px;border-radius:5px}.vocab-word{text-align:center;margin:35px 0 26px}.vocab-word h3{font-size:clamp(34px,5vw,54px);font-weight:650;letter-spacing:-1.5px;margin:0 0 15px;overflow-wrap:anywhere}.vocab-word>p{color:var(--muted,#777);font-size:17px}.vocab-word>p span{font-size:13px;margin-left:12px}.vocab-recall{display:flex;align-items:center;justify-content:center;gap:5px;margin-top:21px}.vocab-recall i{width:17px;height:4px;background:var(--line,#ddd);border-radius:3px}.vocab-recall i.filled{background:var(--vocab-green)}.vocab-recall span{font-size:11px;color:var(--muted,#777);margin-left:8px}.vocab-prompt{text-align:center;font-size:12px;color:var(--muted,#777);margin-bottom:17px}.vocab-options{display:grid;grid-template-columns:1fr 1fr;gap:12px}.vocab-options button{display:flex;align-items:center;gap:12px;min-height:66px;text-align:left;padding:14px;border:1px solid var(--line,#ddd);background:transparent;color:var(--ink,#222);border-radius:8px;font:inherit;font-size:14px;cursor:pointer;line-height:1.5;overflow-wrap:anywhere}.vocab-options button>span{font-size:11px;color:var(--muted,#777);border:1px solid var(--line,#ddd);border-radius:4px;min-width:23px;height:23px;display:grid;place-items:center}.vocab-options button:hover:enabled,.vocab-options .selected{border-color:var(--vocab-green);background:var(--vocab-soft)}.vocab-options .correct{border-color:#538265;background:color-mix(in srgb,#538265 12%,transparent)}.vocab-options .incorrect{border-color:#bd705e;background:color-mix(in srgb,#bd705e 12%,transparent)}.vocab-options button:disabled{cursor:default;opacity:1}.vocab-options .app-icon{margin-left:auto;flex-shrink:0}.vocab-card-bottom{margin-top:24px}.vocab-feedback{background:var(--vocab-soft);padding:20px;border-radius:8px;margin-top:18px}.vocab-feedback strong{font-size:15px}.vocab-feedback p{font-size:12px;line-height:1.7;color:var(--muted,#777);margin:7px 0}.vocab-feedback .vocab-example{font-size:15px;color:var(--ink,#222);margin-top:18px}.vocab-feedback-actions{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-top:14px;font-size:12px}.vocab-feedback--wrong{background:color-mix(in srgb,#bd705e 9%,transparent)}.vocab-welcome{min-height:490px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;gap:20px;max-width:440px;margin:auto}.vocab-welcome-icon{height:80px;width:80px;border-radius:50%;background:var(--vocab-soft);color:var(--vocab-green);display:grid;place-items:center}.vocab-welcome h3{font-size:26px;line-height:1.4;margin:0}.vocab-welcome p{font-size:13px;color:var(--muted,#777);line-height:1.9;margin:0}.vocab-welcome .page-kicker{font-size:10px;letter-spacing:2px}.vocab-word-list>header,.vocab-stats>header{display:flex;justify-content:space-between;gap:20px;align-items:center;margin-bottom:24px}.vocab-library>header p,.vocab-word-list>header p,.vocab-stats>header p{margin-top:8px}.vocab-settings,.vocab-word-list,.vocab-stats,.vocab-import{padding:26px}.vocab-settings p{margin-top:8px}.vocab-settings form{display:flex;align-items:end;gap:14px;flex-wrap:wrap;margin-top:20px}.vocab-settings label{display:grid;gap:8px;font-size:12px}.vocab-settings label:first-child{max-width:170px}.vocab-word-controls{display:flex;gap:12px;margin:20px 0}.vocab-search{position:relative;flex:1}.vocab-search .app-icon{position:absolute;left:12px;top:13px}.vocab-search input{padding-left:37px;width:100%}.vocab-word-controls select{max-width:130px}.vocab-word-row{padding:20px 0;border-top:1px solid var(--line,#eee);display:flex;justify-content:space-between;align-items:center;gap:20px}.vocab-word-row h4{font-size:20px;margin:0 0 9px}.vocab-word-row h4>span{font-weight:400;color:var(--muted,#777);font-size:12px;margin-left:10px}.vocab-word-row p{font-size:14px;margin:0 0 9px}.vocab-word-row small{font-size:12px;color:var(--muted,#777);line-height:1.7}.vocab-word-row-meta{min-width:145px;display:grid;justify-items:end;gap:9px;font-size:11px}.vocab-pagination{display:flex;justify-content:flex-end;align-items:center;gap:12px;font-size:12px;margin-top:20px}.vocab-pagination>span{margin-right:auto}.vocab-import{margin-bottom:25px}.vocab-import>p{margin:12px 0}.vocab-import-tools{display:flex;gap:12px;flex-wrap:wrap;margin:15px 0}.vocab-import-tools input[type=file]{max-width:200px;font-size:12px}.vocab-import>label{font-size:12px}.vocab-json{display:block;width:100%;height:290px;margin:10px 0;resize:vertical;font-family:monospace;font-size:12px;line-height:1.6}.vocab-rights{display:flex;gap:9px;align-items:flex-start;line-height:1.7;margin:18px 0}.vocab-rights input{margin-top:4px}.vocab-history{height:200px;display:grid;grid-template-columns:repeat(14,1fr);gap:12px;padding:12px 0 0}.vocab-history-day{display:flex;flex-direction:column;gap:9px;align-items:center;min-width:0}.vocab-history-day>div{height:125px;width:100%;display:flex;align-items:end}.vocab-history-day i{display:block;background:var(--vocab-green);width:100%;border-radius:4px 4px 0 0;min-height:3px}.vocab-history-day i.empty{background:var(--line,#ddd)}.vocab-history-day small,.vocab-history-day>span{font-size:10px;color:var(--muted,#777)}.vocab-history-table>div{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;border-top:1px solid var(--line,#eee);padding:12px 0;font-size:12px;color:var(--muted,#777)}.vocab-how{padding:20px;font-size:12px;line-height:1.9;color:var(--muted,#777)}.vocab-how summary{cursor:pointer;color:var(--ink,#222)}.vocab-how ul{padding-left:20px;margin-bottom:0}.vocabulary-page button:focus-visible,.vocabulary-page summary:focus-visible{outline:3px solid var(--vocab-green);outline-offset:3px}.vocabulary-page button:disabled:not(.vocab-options button){opacity:.55;cursor:wait}@media(max-width:1050px){.vocab-study-layout{grid-template-columns:230px minmax(0,1fr)}.vocab-study-card{padding:22px}.vocab-options{grid-template-columns:1fr}.vocab-metrics{gap:10px}.vocab-metrics article{padding:17px}}@media(max-width:720px){.vocab-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.vocab-study-layout{grid-template-columns:1fr}.vocab-book-summary{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;padding:18px}.vocab-book-summary>*:not(button){grid-column:1/-1}.vocab-book-summary>button:first-of-type{grid-column:1/-1}.vocab-book-summary .button{font-size:11px;min-width:0;padding:9px;gap:5px}.vocab-tabs{overflow:auto;gap:0}.vocab-tabs button{padding:13px 16px;white-space:nowrap;font-size:13px}.vocab-word-list>header{align-items:flex-start;flex-direction:column}.vocab-word-list,.vocab-stats{padding:18px}.vocab-word-row{align-items:flex-start;gap:12px}.vocab-word-row h4>span{display:block;margin:6px 0 0}.vocab-word-row-meta{min-width:105px}.vocab-history{gap:5px}.vocab-history-day small{font-size:8px;writing-mode:vertical-rl}.vocab-history-table>div{font-size:10px;gap:6px}.vocab-word-controls{flex-wrap:wrap}.vocab-search{min-width:100%}.vocab-card-bottom{align-items:flex-end}.vocab-feedback-actions{align-items:flex-start;flex-direction:column}.vocab-card-top{font-size:10px}.vocab-study-card{padding:20px}.vocab-welcome{min-height:390px}}
</style>
