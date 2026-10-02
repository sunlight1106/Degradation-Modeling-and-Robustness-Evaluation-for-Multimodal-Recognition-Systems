// Mounted VocabularyView regression. Every HTTP response is synthetic.
import { createApp, nextTick } from 'vue'
import VocabularyView from '../views/VocabularyView.vue'
import { tokenStorage } from '../api/client.ts'

const passed = []
const fixture = document.getElementById('fixture')
const assert = (value, message) => { if (!value) throw new Error(message); passed.push(message) }
const wait = async (until = () => true) => {
  for (let i = 0; i < 100; i++) {
    await new Promise(resolve => setTimeout(resolve, 5)); await nextTick()
    if (until()) return
  }
  throw new Error('Timed out waiting for vocabulary UI')
}
const button = text => [...fixture.querySelectorAll('button')].find(el => el.textContent.includes(text))
const card = () => fixture.querySelector('.vocab-study-card')
const options = () => [...fixture.querySelectorAll('.vocab-options button')]
const key = (value, target = document.activeElement, extra = {}) => {
  const event = new window.KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true, ...extra })
  target.dispatchEvent(event)
  return event
}
const click = async target => { target.focus(); target.click(); await nextTick() }
// jsdom does not implement trusted keyboard activation. Check that the global
// listener leaves the key alone, then supply the browser's one native click.
const nativeActivate = async (target, value) => {
  target.focus()
  const count = nextCalls.length + answerCalls.length
  assert(!key(value, target).defaultPrevented, `Native ${value === ' ' ? 'Space' : value} on ${target.textContent.trim()} is not prevented`)
  assert(nextCalls.length + answerCalls.length === count, 'Button keydown does not also invoke the global shortcut')
  target.click(); await nextTick()
}
const envelope = data => new Response(JSON.stringify({ success: true, data }), { status: 200, headers: { 'Content-Type': 'application/json' } })
const dashboard = {
  settings: { zoneId: 'Etc/UTC', dailyGoal: 10, selectedBookId: 'book-1', masteryTarget: 4 },
  books: [{ id: 'book-1', title: 'Original test book', description: 'Synthetic words', attribution: 'Original', level: 'starter', owned: false, totalWords: 8, learned: 1, learning: 1, due: 1, mistakes: 1 }],
  today: { date: '2026-10-02', answers: 0, correct: 0, learned: 0, reviews: 0 },
  history: [], streak: 0, dueTotal: 1, starredTotal: 0,
}
const nextCalls = [], answerCalls = []
let pendingNext, pendingAnswer, pendingDashboard, holdDashboard = true, app
const priorFetch = window.fetch
window.fetch = async (url, init = {}) => {
  const path = String(url)
  if (path.endsWith('/vocabulary/dashboard')) {
    if (holdDashboard) return new Promise(resolve => { pendingDashboard = () => { pendingDashboard = null; resolve(envelope(dashboard)) } })
    return envelope(dashboard)
  }
  if (path.endsWith('/vocabulary/next')) {
    const payload = JSON.parse(init.body)
    nextCalls.push(payload)
    return new Promise(resolve => {
      pendingNext = (empty = false) => {
        pendingNext = null
        resolve(envelope({
          question: empty ? null : {
            id: `question-${nextCalls.length}`, term: `word-${nextCalls.length}`, ipa: '/wɜrd/', pos: 'n.', mode: payload.mode,
            learningCorrect: 0, masteryTarget: 4, expiresAt: '2026-10-02T23:00:00Z',
            options: [1, 2, 3, 4].map(index => ({ id: `option-${index}`, meaning: `Meaning ${index}` })),
          },
          reason: empty ? '今天的复习已完成' : null, remaining: empty ? 0 : 4,
        }))
      }
    })
  }
  if (/\/vocabulary\/questions\/[^/]+\/answer$/.test(path)) {
    const payload = JSON.parse(init.body)
    answerCalls.push({ path, ...payload })
    return new Promise(resolve => {
      pendingAnswer = () => {
        pendingAnswer = null
        resolve(envelope({ questionId: `question-${nextCalls.length}`, wordId: 'word-1', correct: true, correctOptionId: payload.optionId,
          meaning: 'Synthetic meaning', example: 'An original example.', exampleTranslation: '原创例句。', learningCorrect: 1,
          masteryTarget: 4, newlyLearned: false, dueDate: null, reviewStage: 0, starred: false, message: '继续加油。' }))
      }
    })
  }
  throw new Error(`Unexpected vocabulary request: ${path}`)
}
const resolveQuestion = async () => {
  assert(!!pendingNext, 'Question request is pending')
  pendingNext()
  await wait(() => options().length === 4 && !options()[0].disabled)
  assert(document.activeElement === card(), 'Loaded question receives focus without requiring a body click')
  assert(card().tabIndex === -1 && document.getElementById(card().getAttribute('aria-labelledby'))?.textContent.startsWith('word-'), 'Focused question region has an accessible name and stays out of normal tab order')
}
const resolveAnswer = async () => {
  assert(!!pendingAnswer, 'Answer request is pending')
  pendingAnswer()
  await wait(() => button('下一个单词') && !button('下一个单词').disabled)
  assert(document.activeElement === button('下一个单词'), 'Answer completion focuses the enabled Continue button')
  assert(options().every(option => option.disabled), 'Answered options stay disabled')
}

try {
  tokenStorage.set('synthetic-vocabulary-session')
  app = createApp(VocabularyView); app.mount(fixture)
  await wait(() => pendingDashboard)
  key('1', document.body)
  assert(answerCalls.length === 0 && nextCalls.length === 0, 'Initial dashboard loading ignores answer shortcuts')
  holdDashboard = false; pendingDashboard()
  await wait(() => button('学习新词'))

  await click(button('学习新词'))
  assert(nextCalls.length === 1 && nextCalls[0].mode === 'LEARN', 'Focused Learn button starts one learn request')
  key('1', document.body); key('Enter', document.body)
  assert(answerCalls.length === 0 && nextCalls.length === 1 && button('学习新词').disabled, 'Question loading disables controls and ignores keyboard submission')
  await resolveQuestion()

  for (const value of ['1', '2', '3', '4']) {
    const before = answerCalls.length
    const first = key(value)
    key(value)
    await nextTick()
    assert(first.defaultPrevented && answerCalls.length === before + 1 && answerCalls.at(-1).optionId === `option-${value}`, `Keyboard ${value} submits exactly its numbered option once despite a double press`)
    assert(options().every(option => option.disabled) && card().getAttribute('aria-busy') === 'true', 'Pending answer disables options and marks the study region busy')
    key('Enter', document.body)
    assert(!pendingNext, 'Enter cannot advance while an answer is pending')

    if (value === '1') {
      holdDashboard = true; pendingAnswer()
      await wait(() => pendingDashboard && button('下一个单词'))
      assert(button('下一个单词').disabled && document.activeElement === card(), 'Focus is not moved to the disabled Continue button while statistics refresh')
      key('2'); key('Enter', document.body)
      assert(answerCalls.length === before + 1 && !pendingNext, 'Statistics refresh also prevents duplicate answers and early advancement')
      holdDashboard = false; pendingDashboard()
      await wait(() => !button('下一个单词').disabled)
      assert(document.activeElement === button('下一个单词'), 'Continue receives focus only after refresh and busy-state rendering finish')
    } else await resolveAnswer()

    const nextBefore = nextCalls.length
    key('1', document.body)
    assert(answerCalls.length === before + 1, 'Number keys cannot answer an already completed question again')
    await nativeActivate(button('下一个单词'), value === '2' ? ' ' : 'Enter')
    key('Enter', document.body)
    assert(nextCalls.length === nextBefore + 1, 'Native Continue activation plus a repeated key requests one next question')
    await resolveQuestion()
  }

  const beforeProtectedKeys = answerCalls.length
  for (const extra of [{ ctrlKey: true }, { altKey: true }, { metaKey: true }, { repeat: true }, { isComposing: true }]) {
    assert(!key('1', card(), extra).defaultPrevented, 'Modified, held, and composing keys are not intercepted')
  }
  const handled = new window.KeyboardEvent('keydown', { key: '1', bubbles: true, cancelable: true })
  handled.preventDefault(); card().dispatchEvent(handled)
  assert(answerCalls.length === beforeProtectedKeys, 'Modified, composing, repeating, and already-handled keys never submit')
  assert(!key('Enter', card()).defaultPrevented && !key(' ', card()).defaultPrevented && answerCalls.length === beforeProtectedKeys, 'Enter and Space on an unanswered question do not pick an option')

  const editable = document.createElement('div')
  editable.innerHTML = '<input type="text"><textarea></textarea><select><option>1</option></select><div contenteditable="true"><span tabindex="0">editable child</span></div><div contenteditable="plaintext-only" tabindex="0">plain editor</div>'
  card().append(editable)
  for (const target of editable.querySelectorAll('input, textarea, select, span, [contenteditable="plaintext-only"]')) {
    target.focus()
    assert(!key('1', target).defaultPrevented && !key('Enter', target).defaultPrevented && !key(' ', target).defaultPrevented, 'Editable controls and contenteditable descendants keep their own keyboard behavior')
  }
  assert(answerCalls.length === beforeProtectedKeys, 'Typing in an editable control never submits an answer')
  editable.remove()

  await click(button('学习设置'))
  const goal = fixture.querySelector('.vocab-settings input[type="number"]')
  goal.focus(); key('1', goal); key('1', document.body)
  assert(answerCalls.length === beforeProtectedKeys, 'Actual settings inputs and the open settings panel suppress answer shortcuts')
  await click(button('取消'))

  for (const [index, value] of [[1, 'Enter'], [2, ' ']]) {
    const before = answerCalls.length
    await nativeActivate(options()[index], value)
    assert(answerCalls.length === before + 1 && answerCalls.at(-1).optionId === `option-${index + 1}`, 'Native answer-button activation submits only the focused option once')
    await resolveAnswer()
    await nativeActivate(button('下一个单词'), 'Enter')
    await resolveQuestion()
  }

  await nativeActivate(button('到期复习'), ' ')
  assert(nextCalls.at(-1).mode === 'REVIEW', 'Native Space starts review mode')
  await resolveQuestion()
  key('4'); await nextTick(); await resolveAnswer()
  card().focus()
  const reviewCalls = nextCalls.length
  assert(key('Enter').defaultPrevented, 'Enter on the answered study region advances the question')
  key('Enter')
  assert(nextCalls.length === reviewCalls + 1 && nextCalls.at(-1).mode === 'REVIEW', 'Review continuation preserves mode and suppresses duplicate requests')
  await resolveQuestion()

  key('1'); await nextTick(); await resolveAnswer()
  await nativeActivate(button('下一个单词'), 'Enter')
  pendingNext(true)
  await wait(() => fixture.querySelector('#vocab-welcome-title')?.textContent === '今天的复习已完成' && card().getAttribute('aria-busy') === 'false')
  assert(document.activeElement === card() && card().getAttribute('aria-labelledby') === 'vocab-welcome-title', 'Empty review completion focuses the named completion region')
  const completedCount = answerCalls.length
  key('1')
  assert(answerCalls.length === completedCount, 'Empty completion state cannot submit an answer')

  await nativeActivate(button('开始学习'), 'Enter')
  assert(nextCalls.at(-1).mode === 'LEARN', 'Welcome Start button retains native Enter activation')
  const finishLateQuestion = pendingNext
  app.unmount(); app = null
  const outside = document.createElement('input'); fixture.append(outside); outside.focus()
  finishLateQuestion(); await wait()
  assert(document.activeElement === outside && !fixture.querySelector('.vocab-study-card'), 'A question resolving after unmount cannot recreate the view or steal focus')
  const finalCalls = nextCalls.length + answerCalls.length
  key('1', document.body); key('Enter', document.body)
  assert(nextCalls.length + answerCalls.length === finalCalls, 'Unmount removes the global keyboard listener')

  document.getElementById('results').textContent = `PASS ${passed.length}\n` + passed.join('\n')
  document.documentElement.dataset.result = 'passed'
} catch (error) {
  document.getElementById('results').textContent = `FAIL: ${error.stack}\nPassed:\n` + passed.join('\n')
  document.documentElement.dataset.result = 'failed'
} finally {
  app?.unmount(); tokenStorage.clear(); window.fetch = priorFetch
}
