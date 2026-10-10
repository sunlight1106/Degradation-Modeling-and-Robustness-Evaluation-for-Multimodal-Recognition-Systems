import {createApp,h,reactive,nextTick} from 'vue'
import {createRouter,createMemoryHistory} from 'vue-router'
import ReportButton from '../components/ReportButton.vue'
import ModerationView from '../views/ModerationView.vue'
import SafetyView from '../views/SafetyView.vue'
import {tokenStorage} from '../api/client.ts'
const fixture=document.getElementById('fixture'),passed=[]
const assert=(ok,label)=>{if(!ok)throw new Error(label);passed.push(label)}
const wait=async test=>{for(let i=0;i<150;i++){await new Promise(r=>setTimeout(r,5));await nextTick();if(test())return}throw new Error('Moderation UI timed out')}
const button=text=>[...fixture.querySelectorAll('button')].find(b=>b.textContent.includes(text))
const set=async(el,value,event='input')=>{el.value=value;el.dispatchEvent(new Event(event,{bubbles:true}));await nextTick()}
const envelope=data=>new Response(JSON.stringify({success:true,data}),{headers:{'Content-Type':'application/json'}})
const report={id:'synthetic-report',source_type:'CHAT',target_name:'Synthetic user',target_identity:'PKB-00000000000000000000000000000001',reason:'Synthetic evidence',status:'PENDING',urgent:false,created_at:'2026-10-10T01:00:00Z',evidence:'<script>never execute</script>'}
const penalty={id:'synthetic-penalty',report_id:report.id,kind:'FEATURE',features:'CHAT',reason:'Verified reason',automatic:false,created_at:'2026-10-10T01:00:00Z',expires_at:'2099-01-01T00:00:00Z'}
let app,release,pendingReport,reviewBody,appealBody
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]})
async function mount(component){app=createApp(component);app.use(router);app.mount(fixture);await nextTick()}
try{
 tokenStorage.set('synthetic-session');await router.push('/app/moderation');await router.isReady()
 window.fetch=async(path,init)=>{pendingReport=JSON.parse(init.body);return new Promise(resolve=>release=()=>resolve(envelope({id:'synthetic'})))}
 const props=reactive({type:'CHAT',sourceId:11});await mount({setup:()=>()=>h(ReportButton,props)})
 button('举报').click();await nextTick();assert(button('提交举报').disabled,'Empty reasons cannot submit a report')
 await set(fixture.querySelector('textarea'),'Describe the real message');fixture.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await wait(()=>release)
 assert(pendingReport.sourceId==='11'&&pendingReport.type==='CHAT','A report binds the selected real message')
 props.sourceId=12;await nextTick();release();await new Promise(r=>setTimeout(r,10));await nextTick();assert(!fixture.textContent.includes('已举报'),'A stale response cannot mark another message as reported');app.unmount()
 window.fetch=async(path,init)=>{
  const p=String(path);if(p.includes('/moderation/reports?'))return envelope({items:[report],total:1,page:0,features:{CHAT:'私聊与站内信',NOTES:'笔记与知识库'}})
  if(p.endsWith('/reports/synthetic-report'))return envelope({report,penalties:report.status==='RESOLVED'?[penalty]:[],events:[]})
  if(p.endsWith('/review')){reviewBody=JSON.parse(init.body);report.status='RESOLVED';report.review_reason=reviewBody.reason;return envelope(null)}
  throw new Error('Unexpected moderation request')
 }
 await mount(ModerationView);await wait(()=>button('Synthetic user'));button('Synthetic user').click();await wait(()=>fixture.querySelector('.case-evidence'))
 assert(fixture.querySelector('.case-evidence').textContent==='<script>never execute</script>'&&!fixture.querySelector('.case-evidence script'),'Evidence is rendered as safe text')
 assert(button('确认审核决定').disabled,'A review requires an explicit reason')
 await set(fixture.querySelector('.review-form select'),'FEATURE','change');await set(fixture.querySelector('.review-form textarea'),'Verified reason')
 assert(button('确认审核决定').disabled,'Feature restriction requires a selected feature')
 const checkbox=fixture.querySelector('input[type=checkbox]');checkbox.checked=true;checkbox.dispatchEvent(new Event('change',{bubbles:true}));await nextTick()
 await set(fixture.querySelector('input[type=number]'),'2');await set(fixture.querySelector('.duration-row select'),'1440','change')
 fixture.querySelector('.review-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await wait(()=>reviewBody&&fixture.textContent.includes('提前解除处罚'))
 assert(reviewBody.minutes===2880&&reviewBody.features[0]==='CHAT','A two-day penalty becomes 2880 minutes with explicit features')
 assert(fixture.textContent.includes('Verified reason'),'The recorded decision remains visible after review');app.unmount()
 window.fetch=async(path,init)=>{if(String(path).endsWith('/mine'))return envelope({reports:[],penalties:[penalty]});if(String(path).endsWith('/appeal')){appealBody=JSON.parse(init.body);penalty.appeal=appealBody.reason;return envelope(null)}throw new Error('Unexpected safety request')}
 await mount(SafetyView);await wait(()=>button('提交申诉'));assert(button('提交申诉').disabled,'An empty appeal is disabled');await set(fixture.querySelector('textarea'),'Please reconsider the context');fixture.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await wait(()=>fixture.textContent.includes('申诉已提交'))
 assert(appealBody.reason==='Please reconsider the context','Users can submit a reasoned appeal without moderator controls');assert(!fixture.textContent.includes('确认审核决定'),'The personal safety page exposes no review controls')
 document.documentElement.dataset.result='passed';document.getElementById('results').textContent=`PASS (${passed.length} checks)\n`+passed.join('\n')
}catch(e){document.documentElement.dataset.result='failed';document.getElementById('results').textContent=String(e?.stack||e)}finally{app?.unmount();tokenStorage.clear()}
