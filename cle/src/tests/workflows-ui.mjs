import { createApp,nextTick } from 'vue'
import { createMemoryHistory,createRouter } from 'vue-router'
import AccountClosure from '../components/AccountClosure.vue'
import { authStore } from '../stores/auth.ts'
import { tokenStorage } from '../api/client.ts'
const fixture=document.getElementById('fixture'),passed=[]
const assert=(ok,label)=>{if(!ok)throw new Error(label);passed.push(label)}
const wait=async predicate=>{for(let i=0;i<200;i++){await new Promise(r=>setTimeout(r,5));await nextTick();if(predicate())return}throw new Error('Workflow UI timed out')}
const button=text=>[...fixture.querySelectorAll('button')].find(el=>el.textContent.includes(text))
const set=async(el,value)=>{el.value=value;el.dispatchEvent(new Event('input',{bubbles:true}));await nextTick()}
const envelope=data=>new Response(JSON.stringify({success:true,data}),{headers:{'Content-Type':'application/json'}})
let now=0,closeCalls=0,app;const originalPerformance=globalThis.performance,originalFetch=window.fetch
Object.defineProperty(globalThis,'performance',{configurable:true,value:{now:()=>now}})
window.fetch=async(path,init)=>{const body=JSON.parse(init.body);if(String(path).endsWith('/closure/prepare'))return envelope({token:'synthetic-single-use',mode:body.mode,waitSeconds:5,expiresAt:'2099-01-01T00:00:00Z',notes:1});if(String(path).endsWith('/closure/confirm')){closeCalls++;assert(body.confirmation==='停用账号'&&body.currentPassword==='SyntheticPassword123!','Final confirmation includes the required phrase and password');return envelope(null)}throw new Error('Unexpected workflow request')}
try{
 authStore.state.user={id:301,username:'synthetic',displayName:'Synthetic',roleCode:'VIEWER',permissions:[]};tokenStorage.set('synthetic-session')
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});await router.push('/app/settings');await router.isReady();app=createApp(AccountClosure);app.use(router);app.mount(fixture);await nextTick()
 await set(fixture.querySelector('input[type=password]'),'SyntheticPassword123!');button('继续').click();await wait(()=>button('确认停用账号'))
 const text=fixture.querySelector('input[autocomplete=off]');await set(text,'停用账号')
 assert(button('确认停用账号').disabled,'Confirmation stays disabled during the five second wait');button('确认停用账号').click();await nextTick();assert(closeCalls===0,'A click before the deadline cannot submit closure')
 now=6000;await wait(()=>!button('确认停用账号').disabled);button('确认停用账号').click();await wait(()=>closeCalls===1&&!authStore.state.user)
 assert(tokenStorage.get()===null,'Successful suspension clears the browser session');await wait(()=>router.currentRoute.value.query.mode==='reactivate');assert(router.currentRoute.value.path==='/login','Suspension leads to the recovery login page')
 assert(!JSON.stringify(localStorage).includes('SyntheticPassword123!'),'Account credentials are never remembered in browser storage')
 document.documentElement.dataset.result='passed';document.getElementById('results').textContent=`PASS (${passed.length} checks)\n`+passed.join('\n')
}catch(e){document.documentElement.dataset.result='failed';document.getElementById('results').textContent=e.stack||e.message;throw e}
finally{app?.unmount();window.fetch=originalFetch;Object.defineProperty(globalThis,'performance',{configurable:true,value:originalPerformance});authStore.clearSession()}
