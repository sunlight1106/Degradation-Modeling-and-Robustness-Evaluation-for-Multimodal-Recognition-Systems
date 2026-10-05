import { createApp, nextTick } from 'vue'
import PersonalAiMemory from '../components/PersonalAiMemory.vue'
import PersonalAiTraining from '../components/PersonalAiTraining.vue'
import { personalApi } from '../api/personal.ts'

const fixture = document.getElementById('fixture'), passed = []
const assert = (condition, message) => { if (!condition) throw new Error(message); passed.push(message) }
const wait = async check => { for(let i=0;i<100;i++){ await new Promise(resolve=>setTimeout(resolve,10)); await nextTick(); if(check())return } throw new Error('UI timed out') }
const button = text => [...fixture.querySelectorAll('button')].find(el=>el.textContent.includes(text))
const value = async (selector,text) => { const element=fixture.querySelector(selector);element.value=text;element.dispatchEvent(new Event('input',{bubbles:true}));await nextTick() }
let application, items = [], createCount = 0, removed = [], predictor, resolvePrediction
window.confirm = () => true
personalApi.memories = async () => structuredClone(items)
personalApi.saveMemory = async (id, request) => {
  const saved={id:id||'own-memory',...request,revision:(request.revision||0)+1}
  items=[...items.filter(item=>item.id!==saved.id),saved];return saved
}
personalApi.deleteMemory = async (id,revision) => { removed.push({id,revision});items=items.filter(item=>item.id!==id) }
try {
  application=createApp(PersonalAiMemory);application.mount(fixture)
  await wait(()=>fixture.textContent.includes('还没有记忆'))
  await value('input[maxlength="100"]','学习偏好'); await value('textarea','用中文解释 Java')
  fixture.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))
  await wait(()=>fixture.querySelector('.ai-memory-item'))
  assert(items[0].enabled,'New memory can be enabled across personal providers')
  button('停用').click();await wait(()=>fixture.querySelector('.ai-memory-item small').textContent==='停用')
  assert(items[0].enabled===false,'Disable preserves the entry without sending it')
  button('编辑').click();await nextTick();await value('textarea','改成简短说明')
  fixture.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))
  await wait(()=>fixture.querySelector('.ai-memory-item p').textContent==='改成简短说明')
  assert(items[0].revision===3,'Editing sends the current revision')
  button('删除').click();await wait(()=>!fixture.querySelector('.ai-memory-item'))
  assert(removed[0].revision===3,'Delete uses optimistic concurrency revision')
  application.unmount()
  let finishLoad
  personalApi.memories=()=>new Promise(resolve=>finishLoad=resolve)
  application=createApp(PersonalAiMemory);application.mount(fixture);await nextTick();application.unmount()
  finishLoad([{id:'late',title:'private late result',body:'secret',enabled:true,revision:0}]);await nextTick()
  assert(!fixture.textContent.includes('private late result'),'Account changes cannot restore late private memory responses')

  const completed={id:'my-training',name:'自己的训练',status:'COMPLETED',message:'训练完成',epoch:15,epochs:15,samples:12,labels:['学习','生活'],metrics:[{epoch:15,loss:.2,accuracy:.75}],trainSamples:10,validationSamples:2}
  personalApi.trainingEnvironment=async()=>({ready:true,version:'2.10.0+cpu',device:'CPU'})
  personalApi.trainingJobs=async()=>[completed]
  personalApi.predictTraining=async(id,text)=>{predictor={id,text};return new Promise(resolve=>resolvePrediction=resolve)}
  application=createApp(PersonalAiTraining);application.mount(fixture);await wait(()=>fixture.textContent.includes('75.0%'))
  assert(fixture.textContent.includes('2.10.0+cpu'),'Training shows verified environment status')
  assert(button('开始训练').disabled,'Training cannot start without samples')
  await value('textarea','一条新样本');fixture.querySelector('.training-predict').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await nextTick()
  assert(predictor.id==='my-training'&&predictor.text==='一条新样本','Prediction targets the selected own job')
  application.unmount();resolvePrediction([{label:'private-prediction',score:.9}]);await nextTick()
  assert(!fixture.textContent.includes('private-prediction'),'Late prediction cannot reappear after account changes')
  document.documentElement.dataset.result='passed';document.getElementById('results').textContent=`PASS (${passed.length} assertions)\n${passed.join('\n')}`
} catch(error) { document.documentElement.dataset.result='failed';document.getElementById('results').textContent=error.stack;throw error }
finally { application?.unmount() }
