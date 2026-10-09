import { createApp, nextTick } from 'vue'
import VocabularyView from '../views/VocabularyView.vue'
import { tokenStorage } from '../api/client.ts'
const fixture=document.getElementById('fixture'),passed=[]
const assert=(value,message)=>{if(!value)throw new Error(message);passed.push(message)}
const wait=async check=>{for(let i=0;i<100;i++){await new Promise(r=>setTimeout(r,5));await nextTick();if(check())return}throw new Error('Timed out')}
const button=text=>[...fixture.querySelectorAll('button')].find(b=>b.textContent.includes(text))
const settings={zoneId:null,dailyGoal:10,selectedBookId:null,masteryTarget:4},settingsCalls=[],nextCalls=[]
const book={id:'vocab-context',title:'每日 · 场景与搭配',description:'Synthetic course',level:'starter',owned:false,totalWords:86,learned:0,learning:0,due:0,mistakes:0}
const lesson={term:'work',ipa:'/wɜːk/',pos:'v.',meaning:'工作',memoryCue:'电脑前工作',usageNote:'on + 名词',collocations:[{pattern:'work on sth.',meaning:'处理某事',note:'on + 名词'}],example:'I work on a project.',exampleTranslation:'我在做项目。'}
const original=window.fetch;let app
window.fetch=async(url,init={})=>{
  const path=String(url);let data
  if(path.endsWith('/dashboard'))data={settings:{...settings},books:[book],today:{answers:0,correct:0,learned:0,reviews:0},history:[],streak:0,dueTotal:0,starredTotal:0}
  else if(path.endsWith('/settings')){const payload=JSON.parse(init.body);settingsCalls.push(payload);await new Promise(r=>setTimeout(r,20));Object.assign(settings,payload);data={...settings}}
  else if(path.endsWith('/next')){nextCalls.push(JSON.parse(init.body));data={question:{id:'first-use',term:'work',ipa:lesson.ipa,pos:'v.',prompt:'工作',practiceKind:'SPELLING',introduced:false,hintLevel:0,mode:'LEARN',options:[],learningCorrect:0,masteryTarget:4},remaining:10}}
  else if(path.endsWith('/lesson'))data=lesson
  else throw new Error('Unexpected request '+path)
  return new Response(JSON.stringify({success:true,data}),{status:200,headers:{'Content-Type':'application/json'}})
}
try{
  tokenStorage.set('synthetic-first-use-session');app=createApp(VocabularyView);app.mount(fixture);await wait(()=>button('开始学习'))
  assert(!fixture.querySelector('dialog'), 'A new account is not interrupted by timezone confirmation')
  button('开始学习').click();button('开始学习').click();await wait(()=>fixture.textContent.includes('work on sth.'))
  assert(settingsCalls.length===1 && settingsCalls[0].zoneId===Intl.DateTimeFormat().resolvedOptions().timeZone, 'First Start saves the computer timezone exactly once')
  assert(nextCalls.length===1 && nextCalls[0].style==='RECALL', 'The same Start action continues directly into guided learning')
  assert(!fixture.querySelector('dialog') && fixture.textContent.includes('电脑前工作'), 'The first lesson is visible without an offscreen settings step')
  document.documentElement.dataset.result='passed';document.getElementById('results').textContent=`PASS ${passed.length}\n${passed.join('\n')}`
}catch(error){document.documentElement.dataset.result='failed';document.getElementById('results').textContent=error.stack}
finally{app?.unmount();window.fetch=original}
