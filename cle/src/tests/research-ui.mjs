import { createApp,nextTick } from 'vue'
import { createRouter,createMemoryHistory } from 'vue-router'
import StudyCards from '../components/StudyCards.vue'
import KnowledgeCollections from '../components/KnowledgeCollections.vue'
import NoteHistory from '../components/NoteHistory.vue'
import { researchApi } from '../api/research.ts'
import { writeDraft,readDraft,draftKey,clearDraft } from '../lib/noteDraft.ts'
const fixture=document.getElementById('fixture'),passed=[]
const assert=(v,m)=>{if(!v)throw new Error(m);passed.push(m)}
const wait=async(check)=>{for(let i=0;i<100;i++){await new Promise(r=>setTimeout(r,10));await nextTick();if(check())return}throw new Error('UI timed out')}
const button=text=>[...fixture.querySelectorAll('button')].find(b=>b.textContent.includes(text))
let app,restored=0,graded=0
window.confirm=()=>true
try{
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});await router.push('/');await router.isReady()
 const card={id:'own',kind:'CARD',title:'Recall',data:{question:'How does it work?',answer:'Private explanation',subject:'Computer science',due:'2020-01-01T00:00:00Z',reviews:0},revision:3}
 researchApi.records=async kind=>kind==='CARD'?[card]:[]
 researchApi.grade=async(row,grade)=>{assert(row.revision===3,'Review sends current revision');graded++;return {...row,revision:4}}
 app=createApp(StudyCards);app.use(router);app.mount(fixture);await wait(()=>fixture.textContent.includes('Recall'))
 button('Recall').click();await nextTick();assert(!fixture.querySelector('.review-card pre'),'Answer remains hidden until requested')
 button('显示答案').click();await nextTick();assert(fixture.querySelector('.review-card pre').textContent==='Private explanation','Recall reveals the saved answer')
 button('忘记了').click();await wait(()=>graded===1);app.unmount()
 app=createApp(KnowledgeCollections);app.mount(fixture);await nextTick();button('添加资料').click();await nextTick();assert(fixture.querySelectorAll('tbody tr').length===1,'Collection adds one shared data row')
 button('看板').click();await nextTick();assert(fixture.querySelector('.collection-board').textContent.includes('新资料'),'Board reads the same collection rows')
 button('月历').click();await nextTick();assert(fixture.querySelectorAll('.collection-calendar>div').length===42,'Calendar displays a complete six-week month grid');app.unmount()
 researchApi.versions=async()=>[{id:'v1',revision:0,title:'Old',createdAt:'2026-01-01'}]
 researchApi.version=async()=>({id:'v1',revision:0,title:'Old',body:'old text'})
 researchApi.restoreVersion=async()=>{restored++}
 app=createApp(NoteHistory,{id:'note',body:'new text',revision:1,dirty:true});app.mount(fixture);button('历史版本').click();await wait(()=>button('版本 0'));button('版本 0').click();await wait(()=>fixture.querySelector('.history-diff'))
 assert(fixture.querySelector('.history-diff').textContent.includes('old text')&&fixture.querySelector('.history-diff').textContent.includes('new text'),'Recovery previews old and current content together')
 assert(button('确认恢复').disabled,'Unsaved changes block history restoration');assert(restored===0,'Preview does not write to the server');app.unmount()
 const d={title:'Private',body:'draft',revision:2,savedAt:'2026-10-07',tags:'',library:'English',status:'DRAFT',contentFormat:'MARKDOWN',parentId:''};writeDraft(draftKey(1,'note'),d)
 assert(readDraft(draftKey(2,'note'))===null,'Draft lookup is isolated by account');assert(readDraft(draftKey(1,'note')).body==='draft','Draft survives editor recreation');clearDraft(draftKey(1,'note'));assert(readDraft(draftKey(1,'note'))===null,'Successful save can remove only its own cached draft')
 document.getElementById('results').textContent=`PASS (${passed.length} assertions)\n`+passed.join('\n');document.documentElement.dataset.result='passed'
}catch(error){document.getElementById('results').textContent=error.stack;document.documentElement.dataset.result='failed'}finally{app?.unmount()}
