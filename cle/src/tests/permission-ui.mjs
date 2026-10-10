import {createApp,nextTick} from 'vue'
import {createRouter,createMemoryHistory} from 'vue-router'
import ModelsView from '../views/ModelsView.vue'
import UploadView from '../views/UploadView.vue'
import BillingView from '../views/BillingView.vue'
import {api,tokenStorage} from '../api/client.ts'
import {authStore} from '../stores/auth.ts'
const fixture=document.getElementById('fixture'),checks=[]
const assert=(value,label)=>{if(!value)throw new Error(label);checks.push(label)}
const wait=async test=>{for(let i=0;i<100;i++){await new Promise(r=>setTimeout(r,5));await nextTick();if(test())return}throw new Error('Permission UI did not settle')}
const original={...api};let app,runtimeCalls=0
try{
 tokenStorage.set('synthetic-permission-session')
 const user={id:701,username:'synthetic-permission',displayName:'Synthetic permission user',roleName:'Admin',roleCode:'ADMIN',permissions:['experiment:run']}
 authStore.state.user=user
 api.models=async()=>[];api.modelRuntime=async()=>{runtimeCalls++;throw new Error('Protected runtime must not be requested')}
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});await router.push('/models');await router.isReady()
 app=createApp(ModelsView).use(router);app.mount(fixture);await wait(()=>!fixture.textContent.includes('正在加载'))
 assert(runtimeCalls===0&&!fixture.querySelector('[role="alert"]'),'Public models remain readable without requesting restricted runtime information');app.unmount()
 await router.push('/app/upload');app=createApp(UploadView).use(router);app.mount(fixture);await nextTick();await new Promise(r=>setTimeout(r,10))
 assert(runtimeCalls===0,'Partial experiment access does not request a forbidden model runtime')
 assert(!fixture.querySelector('input[type=file]')&&fixture.textContent.includes('没有上传权限'),'An account without upload access sees an explanation instead of an active upload form')
 assert([...fixture.querySelectorAll('button')].find(b=>b.textContent.includes('运行实验'))?.disabled,'An incomplete experiment permission set cannot start a run');app.unmount()
 authStore.state.user={...user,roleCode:'VIEWER',permissions:['billing:read','billing:manage','credential:manage']}
 api.billing=async()=>({wallet:{balanceCny:0,monthSpentCny:0,monthlyQuotaCny:0,remainingQuotaCny:0,quotaProgressPercent:0},recentTransactions:[],recentOrders:[]})
 api.adminWallets=async()=>{throw new Error('A delegated billing permission must not request administrator wallets')}
 api.credentials=async()=>{throw new Error('Shared credentials remain administrator-only')}
 await router.push('/app/billing');app=createApp(BillingView).use(router);app.mount(fixture);await wait(()=>fixture.querySelector('.wallet-hero'))
 assert(!fixture.querySelector('.admin-billing-grid')&&!fixture.querySelector('[role="alert"]'),'Delegated billing access loads the personal wallet without exposing administrator controls');app.unmount()
 authStore.state.user=user
 assert(!authStore.has('role:write')&&!authStore.has('file:write'),'An ADMIN role label cannot override temporary restrictions returned by the server')
 api.me=async()=>({...user,permissions:['dashboard:read']})
 await authStore.ensureUser(true)
 assert(authStore.has('dashboard:read')&&!authStore.has('experiment:run')&&tokenStorage.get()==='synthetic-permission-session','Refreshing access updates the current account without logging it out')
 api.me=async()=>{throw new Error('Synthetic network interruption')};await authStore.ensureUser(true)
 assert(authStore.has('dashboard:read'),'A failed background access refresh retains the last verified account')
 document.getElementById('results').textContent=`PASS (${checks.length} assertions)\n`+checks.join('\n');document.documentElement.dataset.result='passed'
}catch(error){document.getElementById('results').textContent=error.stack;document.documentElement.dataset.result='failed'}finally{app?.unmount();Object.assign(api,original);authStore.clearSession()}
