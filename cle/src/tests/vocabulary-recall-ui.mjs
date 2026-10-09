import { createApp, nextTick, ref, h } from 'vue'
import VocabularyRecallCard from '../components/VocabularyRecallCard.vue'
import { tokenStorage } from '../api/client.ts'
const fixture = document.getElementById('fixture'), passed = []
const assert = (value, message) => { if (!value) throw new Error(message); passed.push(message) }
const wait = async check => { for (let i=0;i<100;i++) { await new Promise(r=>setTimeout(r,5)); await nextTick(); if (check()) return } throw new Error('Timed out') }
const button = text => [...fixture.querySelectorAll('button')].find(b=>b.textContent.includes(text))
const lesson = { term: 'work', ipa: '/wɜːk/', pos: 'v.', meaning: '工作', memoryCue: '电脑前做项目', usageNote: 'on 后接名词或 -ing', collocations: [{ pattern: 'work on sth.', meaning: '处理某事', note: 'on + 名词' }], example: 'I work on a project.', exampleTranslation: '我正在做项目。' }
const q = ref({ id:'q1', term:'work', prompt:'工作', practiceKind:'SPELLING', introduced:false, hintLevel:0 })
const answer = ref(null), busy = ref(false), submitted = [], calls = [], skipped = ref(0)
const original = window.fetch
window.fetch = async (url, init={}) => {
  calls.push(String(url))
  const value = String(url).endsWith('/lesson') ? lesson : { level:JSON.parse(init.body).level, text:'想象在电脑前工作' }
  return new Response(JSON.stringify({ success:true, data:value }), { status:200, headers: { 'Content-Type': 'application/json' } })
}
let app
try {
  tokenStorage.set('synthetic-recall-session')
  app=createApp({ setup(){return ()=>h(VocabularyRecallCard,{question:q.value,answer:answer.value,busy:busy.value,onSubmit:value=>submitted.push(value),onSkip:()=>skipped.value++})} }); app.mount(fixture)
  await wait(()=>button('遮住答案'))
  assert(fixture.textContent.includes('work on sth.') && fixture.textContent.includes('/wɜːk/'), 'Introduction contains phonetics, a scene and a full collocation')
  button('遮住答案').click(); await nextTick()
  assert(!fixture.textContent.includes('work on sth.') && !fixture.textContent.includes('/wɜːk/'), 'Recall hides the English answer and phonetic clue')
  const input=fixture.querySelector('input'); input.value='work'; input.dispatchEvent(new Event('input',{bubbles:true})); await nextTick()
  fixture.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true,bubbles:true})); await nextTick()
  assert(submitted.length===1 && submitted[0]==='work', 'Recall submits the written word without guessing a choice')
  busy.value=true; await nextTick(); fixture.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true,bubbles:true})); button('Skip').click()
  assert(submitted.length===1 && skipped.value===0, 'Pending answers cannot submit again or skip')
  busy.value=false; await nextTick(); button('给我一个画面').click(); await wait(()=>fixture.textContent.includes('想象在电脑前'))
  assert(calls.some(path=>path.endsWith('/hint')), 'Hints are requested from the server rather than locally faking evidence')
  button('Skip').click(); await nextTick(); assert(skipped.value===1, 'Skip emits a separate event without marking a word correct')
  answer.value={correct:true,evidence:'PROMPTED',expectedText:'work',lesson,message:'提示后答对',independentCorrect:0,promptedCorrect:1,immediateCorrect:0}; await nextTick()
  assert(fixture.textContent.includes('提示后答对') && fixture.textContent.includes('work on sth.'), 'Feedback restores the lesson and shows prompted evidence')
  await wait(()=>document.activeElement===button('下一个单词'))
  assert(document.activeElement===button('下一个单词'), 'Feedback focuses Continue for the keyboard workflow')
  document.documentElement.dataset.result='passed'; document.getElementById('results').textContent=`PASS ${passed.length}\n${passed.join('\n')}`
} catch(error) { document.documentElement.dataset.result='failed'; document.getElementById('results').textContent=error.stack; }
finally { app?.unmount(); window.fetch=original }
