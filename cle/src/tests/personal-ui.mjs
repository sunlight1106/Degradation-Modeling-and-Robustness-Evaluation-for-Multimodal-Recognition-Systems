import { createApp, reactive, h, nextTick } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { renderMarkdown } from '../lib/markdown.ts'
import NotePersonalTools from '../components/NotePersonalTools.vue'
import PersonalAiSettings from '../components/PersonalAiSettings.vue'
import PersonalRecognitionPanel from '../components/PersonalRecognitionPanel.vue'
import SettingsView from '../views/SettingsView.vue'
import NoteEditorView from '../views/NoteEditorView.vue'
import { themeStore } from '../stores/theme.ts'
import { authStore } from '../stores/auth.ts'
import { tokenStorage, request } from '../api/client.ts'

const passed = []
const fixture = document.getElementById('fixture')
const assert = (value, message) => { if (!value) throw new Error(message); passed.push(message) }
const wait = async (until = () => true) => { for (let i = 0; i < 100; i++) { await new Promise(r => setTimeout(r, 10)); await nextTick(); if (until()) return } throw new Error('Timed out waiting for UI') }
const button = text => Array.from(fixture.querySelectorAll('button')).find(el => el.textContent.includes(text))
const click = async text => { const target = button(text); if (!target) throw new Error(`Missing button: ${text}`); target.click(); await nextTick() }
const setValue = async (el, value) => { el.value = value; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); await nextTick() }
const check = async (el, value) => { el.checked = value; el.dispatchEvent(new Event('change', { bubbles: true })); await nextTick() }
const providers = [{ provider:'OPENAI', displayName:'OpenAI', protocol:'OPENAI_CHAT', baseUrls:['https://api.openai.com/v1'], customEndpointAllowed:false, remoteEnabled:true },{ provider:'GEMINI', displayName:'Gemini', protocol:'GEMINI', baseUrls:['https://generativelanguage.googleapis.com/v1beta'], customEndpointAllowed:false, remoteEnabled:true }]
const settings = [{provider:'OPENAI', model:'synthetic-model', baseUrl:'https://api.openai.com/v1', configured:true, enabled:true, revision:1}]
let pendingPreview, pendingExecute, pendingRecognition, executeCalls = 0, saveCalls = 0, recognitionCalls = 0, logoutCalls = 0
const envelope = data => new Response(JSON.stringify({success:true,data}), {status:200,headers:{'Content-Type':'application/json'}})
window.fetch = async (url, init = {}) => {
  const path = String(url)
  if (path.endsWith('/account/usage')) return envelope({ai:{total:999,succeeded:990,failed:9,knownInputTokens:3000,knownOutputTokens:1000,unknownUsageCalls:3},experiments:{total:11,completed:10,failed:1},files:{count:2,bytes:2048},noteCount:7,recognitionCount:4})
  if (path.endsWith('/personal-ai/usage')) return envelope([{id:'one-recent-call',provider:'OPENAI',model:'synthetic',action:'summarize',status:'SUCCEEDED',inputTokens:5,outputTokens:4,createdAt:'2026-10-02T11:00:00Z'}])
  if (path.endsWith('/account/logout')) { logoutCalls++; return envelope(null) }
  if (path.endsWith('/files') && init.method === 'POST') return envelope({id:'own-file',originalName:'synthetic.png',sha256:'synthetic-hash',scanStatus:'CLEAN'})
  if (path.endsWith('/recognition/results')) return envelope([])
  if (path.endsWith('/recognition/preview')) return envelope({previewToken:'synthetic-image-once',expiresAt:new Date(Date.now()+300000).toISOString(),provider:'OPENAI',model:'synthetic-model',endpoint:'https://api.openai.com/v1/chat/completions',fileId:'own-file',fileName:'synthetic.png',sha256:'synthetic-hash',mime:'image/png',sizeBytes:3,taskType:'RECEIPT',systemPrompt:'Image system prompt',prompt:'Exact image user prompt',outboundBytes:200})
  if (path.endsWith('/recognition/execute')) { recognitionCalls++; return new Promise(resolve=>{ pendingRecognition=()=>resolve(envelope({id:'recognition-1',provider:'OPENAI',model:'synthetic-model',taskType:'RECEIPT',fileName:'synthetic.png',result:'synthetic recognized result',createdAt:'2026-10-02T11:00:00Z'})) }) }
  if (path.endsWith('/providers')) return envelope(providers)
  if (path.endsWith('/settings')) return envelope(settings)
  if (path.endsWith('/settings/OPENAI') && init.method === 'PUT') { saveCalls++; return envelope(settings[0]) }
  if (path.endsWith('/sources/experiments')) return envelope([{taskId:'own-1', title:'我的实验', modelName:'Demo', status:'COMPLETED',createdAt:'2026-10-02T11:00:00Z'}])
  if (path.endsWith('/sources/experiments/preview')) return new Promise(resolve => { pendingPreview = () => resolve(envelope({markdown:'synthetic source',sourceIds:['own-1']})) })
  if (path.endsWith('/personal-ai/preview')) return envelope({previewToken:'synthetic-once',expiresAt:new Date(Date.now()+300000).toISOString(),provider:'OPENAI',model:'synthetic-model',endpoint:'https://api.openai.com/v1/chat/completions',action:'summarize',context:'EXACT PRIVATE CONTEXT',systemPrompt:'synthetic prompt',outboundBytes:100})
  if (path.endsWith('/personal-ai/execute')) { executeCalls++; return new Promise(resolve => { pendingExecute = () => resolve(envelope({action:'summarize',engine:'PERSONAL_AI:OPENAI',result:'synthetic result',items:[]})) }) }
  throw new Error(`Unexpected request ${path}`)
}
const router = createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]})
try {
  const malicious = '<script>alert(1)</script><img src="https://tracking.invalid/pixel" onerror="alert(1)"><iframe src="https://tracking.invalid"></iframe><div style="background:url(https://tracking.invalid)">safe</div>\n\n[x](javascript:alert(1))\n\n![tracker](https://tracking.invalid/pixel)'
  const safe = renderMarkdown(malicious)
  const holder = document.createElement('div'); holder.innerHTML = safe
  assert(!holder.querySelector('script,img,iframe,style,video,audio,source,object,embed'), 'Markdown blocks scripts and all auto-loading media')
  assert(!holder.querySelector('[style],[onerror],[src],[srcset]'), 'Markdown removes event, CSS and resource attributes')
  assert(!Array.from(holder.querySelectorAll('a')).some(a => a.getAttribute('href')?.startsWith('javascript:')), 'Markdown removes javascript links')
  assert(renderMarkdown('## Title\n\n**safe**').includes('<strong>safe</strong>'), 'Safe Markdown still renders')

  const props = reactive({title:'Synthetic', body:'private body'})
  let app = createApp({render:() => h(NotePersonalTools,props)}).use(router); app.mount(fixture)
  await wait(() => fixture.querySelector('select')?.options.length === 2)
  await click('选择实验结果'); await wait(() => fixture.querySelector('.note-source-option input'))
  await check(fixture.querySelector('.note-source-option input'),true)
  await click('预览选中结果'); await wait(() => pendingPreview)
  await check(fixture.querySelector('.note-source-option input'),false); pendingPreview(); await wait()
  assert(!fixture.textContent.includes('synthetic source'), 'Changed experiment selection discards late preview')
  await setValue(fixture.querySelector('select'),'OPENAI')
  await click('摘要'); await wait(() => fixture.querySelector('[aria-label="发送前确认"]'))
  assert(fixture.textContent.includes('EXACT PRIVATE CONTEXT'), 'AI review shows exact outbound context')
  assert(button('确认发送并生成').disabled, 'Provider call requires explicit consent')
  await click('取消，不发送'); assert(executeCalls === 0, 'Canceling preview makes zero provider execution requests')
  await click('摘要'); await wait(() => fixture.querySelector('[aria-label="发送前确认"]'))
  props.body = 'changed'; await nextTick()
  assert(!fixture.querySelector('[aria-label="发送前确认"]'), 'Editing the note invalidates outbound consent')
  await click('摘要'); await wait(() => fixture.querySelector('[aria-label="发送前确认"]'))
  await check(fixture.querySelector('[aria-label="发送前确认"] input[type=checkbox]'),true)
  const send = button('确认发送并生成'); send.click(); send.click(); await nextTick(); await wait(() => pendingExecute)
  assert(executeCalls === 1, 'Repeated confirm click executes once')
  await click('停止等待'); pendingExecute(); await wait()
  assert(!fixture.querySelector('[aria-label="生成结果预览"]'), 'Canceled execution cannot apply a late result')
  await click('摘要'); await wait(() => fixture.querySelector('[aria-label="发送前确认"]'))
  await check(fixture.querySelector('[aria-label="发送前确认"] input[type=checkbox]'),true)
  await click('确认发送并生成'); await wait(() => executeCalls === 2); app.unmount(); pendingExecute(); await wait()
  assert(fixture.children.length === 0, 'Navigation unmount discards pending execution output')

  app = createApp(PersonalAiSettings); app.mount(fixture)
  await wait(() => fixture.querySelector('input[type=password]'))
  await setValue(fixture.querySelector('input[type=password]'),'SYNTHETIC-NOT-A-REAL-KEY')
  await setValue(fixture.querySelector('select'),'GEMINI')
  assert(fixture.querySelector('input[type=password]').value === '', 'Switching providers clears key input')
  await setValue(fixture.querySelector('select'),'OPENAI')
  await setValue(fixture.querySelector('input[type=password]'),'SYNTHETIC-NOT-A-REAL-KEY')
  fixture.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})); await wait(() => saveCalls === 1 && fixture.querySelector('input[type=password]').value === '')
  assert(fixture.querySelector('input[type=password]').value === '', 'Saving clears key input')
  assert(!Object.values(localStorage).some(value => String(value).includes('SYNTHETIC-NOT-A-REAL-KEY')), 'Key is never persisted in localStorage')
  app.unmount()
  tokenStorage.set('synthetic-usage-session')
  authStore.state.user={id:1001,displayName:'Synthetic user',username:'synthetic',permissions:[],roleName:'Researcher',email:'synthetic@example.invalid',createdAt:'2026-10-02T11:00:00Z'}
  await router.replace('/app/settings?section=usage')
  app=createApp(SettingsView).use(router); app.mount(fixture)
  await wait(()=>fixture.querySelector('.settings-metrics--overview'))
  assert(fixture.querySelector('.settings-metrics--overview strong').textContent==='999' && fixture.querySelectorAll('.data-table tbody tr').length===1, 'All-time usage comes from account aggregate while recent history stays separate')
  assert(fixture.textContent.includes('3 次调用没有完整用量数据') && fixture.textContent.includes('失败或停止等待的请求仍可能被供应商计费'), 'Usage explicitly labels unknown tokens and possible charges for failures')
  app.unmount(); authStore.clearSession()

  const originalBlobUrl = URL.createObjectURL
  const originalRevoke = URL.revokeObjectURL
  URL.createObjectURL = () => 'blob:synthetic-image'
  URL.revokeObjectURL = () => {}
  app = createApp(PersonalRecognitionPanel).use(router); app.mount(fixture)
  await wait(() => fixture.querySelector('select')?.options.length === 2)
  const imageInput = fixture.querySelector('input[type=file]')
  Object.defineProperty(imageInput,'files',{value:[new File(['123'],'synthetic.png',{type:'image/png'})],configurable:true})
  imageInput.dispatchEvent(new Event('change',{bubbles:true})); await nextTick()
  assert(button('上传并预览发送内容').disabled, 'Recognition requires explicit vision-model compatibility acknowledgement')
  await check(fixture.querySelector('.settings-check input'),true)
  await click('上传并预览发送内容'); await wait(()=>fixture.querySelector('[aria-label="图片发送前确认"]'))
  assert(fixture.textContent.includes('synthetic-hash') && fixture.textContent.includes('Exact image user prompt'), 'Recognition review identifies exact image and complete prompts')
  assert(button('确认发送并识别').disabled, 'Recognition requires separate image-transmission consent')
  await check(fixture.querySelector('[aria-label="图片发送前确认"] input'),true)
  const recognizeButton=button('确认发送并识别'); recognizeButton.click(); recognizeButton.click(); await wait(()=>pendingRecognition)
  assert(recognitionCalls===1, 'Repeated recognition confirmation executes once')
  await click('停止等待'); pendingRecognition(); await wait()
  assert(!fixture.querySelector('[aria-label="图片识别结果"]'), 'Canceled image recognition discards late result')
  await click('上传并预览发送内容'); await wait(()=>fixture.querySelector('[aria-label="图片发送前确认"]'))
  await setValue(fixture.querySelectorAll('select')[1],'LICENSE_PLATE')
  assert(!fixture.querySelector('[aria-label="图片发送前确认"]'), 'Changing recognition task invalidates preview and consent')
  Object.defineProperty(imageInput,'files',{value:[new File(['invalid'],'wrong.pdf',{type:'application/pdf'})],configurable:true})
  imageInput.dispatchEvent(new Event('change',{bubbles:true})); await nextTick()
  assert(!fixture.querySelector('.recognition-image') && button('上传并预览发送内容').disabled, 'Rejecting invalid image clears prior file to prevent wrong-image submission')
  app.unmount(); URL.createObjectURL=originalBlobUrl; URL.revokeObjectURL=originalRevoke

  themeStore.useAccount(1001); themeStore.setPreference('dark'); themeStore.setAccent('blue'); themeStore.setDensity('compact')
  themeStore.useAccount(1002)
  assert(themeStore.state.preference==='system' && themeStore.state.accent==='rose' && themeStore.state.density==='comfortable', 'Appearance preferences do not bleed into another account')
  themeStore.useAccount(1001)
  assert(themeStore.state.preference==='dark' && themeStore.state.accent==='blue' && themeStore.state.density==='compact', 'Appearance preferences survive returning to the same account')
  tokenStorage.set('synthetic-session-token'); authStore.state.user={id:1001}; await authStore.logout()
  assert(logoutCalls===1 && !tokenStorage.get() && authStore.state.user===null, 'Logout revokes server session and clears browser authentication')
  // Two-tab race simulation: localStorage changes before its queued storage event.
  const priorFetch = window.fetch
  let protectedCalls = 0
  window.fetch = async (...args) => { protectedCalls++; return priorFetch(...args) }
  tokenStorage.set('account-A-token'); authStore.state.user={id:1001}
  localStorage.setItem('personal_platform_token','account-B-token')
  let crossedAccount = false
  try { await request('/personal-ai/settings/OPENAI',{method:'PUT',body:JSON.stringify({apiKey:'SYNTHETIC-A-ONLY'})}) } catch (error) { crossedAccount = error.code==='SESSION_CHANGED' }
  assert(crossedAccount && protectedCalls===0, 'A stale tab cannot send A data using B token before storage event arrives')
  assert(localStorage.getItem('personal_platform_token')==='account-B-token', 'Invalidating stale tab A preserves B token in shared storage')

  window.fetch = priorFetch
  tokenStorage.set('account-A-token'); authStore.state.user={id:1001}
  app=createApp({render:()=>authStore.state.user ? h(PersonalAiSettings,{key:authStore.state.user.id}) : null}); app.mount(fixture)
  await wait(()=>fixture.querySelector('input[type=password]'))
  await setValue(fixture.querySelector('input[type=password]'),'SYNTHETIC-A-ONLY')
  localStorage.setItem('personal_platform_token','account-B-token')
  window.dispatchEvent(new StorageEvent('storage',{key:'personal_platform_token',oldValue:'account-A-token',newValue:'account-B-token'})); await nextTick()
  assert(authStore.state.user===null && !fixture.querySelector('input[type=password]'), 'Cross-tab account switch unmounts private editor and clears key input')
  assert(localStorage.getItem('personal_platform_token')==='account-B-token' && tokenStorage.get()===null, 'Cross-tab switch never silently adopts B authentication')
  app.unmount()

  tokenStorage.set('account-A-token'); authStore.state.user={id:1001}
  let resolveOld
  window.fetch=()=>new Promise(resolve=>{resolveOld=resolve})
  const oldFailure=request('/account/profile').catch(error=>error)
  tokenStorage.set('account-B-token'); authStore.state.user={id:1002}
  resolveOld(new Response(JSON.stringify({success:false,error:{code:'UNAUTHORIZED',message:'old A session expired'}}),{status:401,headers:{'Content-Type':'application/json'}}))
  const oldError=await oldFailure
  assert(oldError.code==='SESSION_CHANGED' && tokenStorage.get()==='account-B-token' && localStorage.getItem('personal_platform_token')==='account-B-token' && authStore.state.user.id===1002, 'Delayed A 401 cannot clear or invalidate newer B login')
  const oldSuccess=request('/account/profile').catch(error=>error)
  tokenStorage.set('account-C-token'); authStore.state.user={id:1003}
  resolveOld(envelope({id:1002,displayName:'old B data'}))
  assert((await oldSuccess).code==='SESSION_CHANGED', 'Old account successful response is discarded after identity changes')
  tokenStorage.set('account-A-token'); authStore.state.user=null
  const oldEnsure=authStore.ensureUser()
  tokenStorage.set('account-B-token'); authStore.state.user={id:1002}; authStore.state.loading=false
  resolveOld(envelope({id:1001,displayName:'old A user'}))
  await oldEnsure
  assert(tokenStorage.get()==='account-B-token' && authStore.state.user.id===1002 && authStore.state.loading===false, 'Delayed ensureUser A response cannot clear or overwrite B state')
  tokenStorage.set('same-token'); authStore.state.user=null
  const repeatedTokenEnsure=authStore.ensureUser()
  tokenStorage.set('same-token'); authStore.state.user={id:1002}; authStore.state.loading=false
  resolveOld(envelope({id:1001,displayName:'obsolete generation'}))
  await repeatedTokenEnsure
  assert(tokenStorage.get()==='same-token' && authStore.state.user.id===1002, 'ensureUser checks authentication generation even when token value repeats')
  window.fetch=priorFetch
  tokenStorage.set('synthetic-note-editor-token'); authStore.state.user={id:1001,permissions:['note:write','note:read']}
  const noteFixture=id=>({id,title:`Note ${id}`,body:`Body ${id}`,tags:[],status:{code:'DRAFT',label:'草稿'},references:[],shareCount:0,createdAt:'2026-10-02T11:00:00Z',updatedAt:'2026-10-02T11:00:00Z'})
  let finishOldSave
  window.fetch=async(url,init={})=>{
    const path=String(url)
    if(path.endsWith('/notes/assist'))return new Response(JSON.stringify({success:false,error:{code:'ASSIST_BODY_TOO_LARGE',message:'内容过长，不会截断正文'}}),{status:413,headers:{'Content-Type':'application/json'}})
    if(path.endsWith('/notes/a')&&init.method==='PATCH')return new Promise(resolve=>{finishOldSave=()=>resolve(envelope({...noteFixture('a'),...JSON.parse(init.body),status:noteFixture('a').status}))})
    if(path.endsWith('/notes/a/shares')||path.endsWith('/notes/b/shares'))return envelope([])
    if(path.endsWith('/notes/a'))return envelope(noteFixture('a'))
    if(path.endsWith('/notes/b'))return envelope(noteFixture('b'))
    return priorFetch(url,init)
  }
  const editorRouter=createRouter({history:createMemoryHistory(),routes:[{path:'/app/settings',component:{template:'<div />'}},{path:'/notes',name:'notes',component:{template:'<div />'}},{path:'/notes/:id',name:'note-edit',component:NoteEditorView}]})
  await editorRouter.push('/notes/a'); await editorRouter.isReady()
  window.confirm=()=>true
  app=createApp({render:()=>h(RouterView)});app.use(editorRouter);app.mount(fixture)
  await wait(()=>fixture.querySelector('textarea')?.value==='Body a'&&!fixture.querySelector('textarea')?.disabled).catch(()=>{throw new Error('Editor A not ready: '+fixture.textContent.slice(0,500))})
  await setValue(fixture.querySelector('textarea'),'New body a');await click('保存');await wait(()=>!!finishOldSave).catch(()=>{throw new Error('Save did not start; button states '+Array.from(fixture.querySelectorAll('button')).slice(0,6).map(b=>b.textContent+':'+b.disabled).join('|'))})
  await editorRouter.push('/notes/b')
  assert(editorRouter.currentRoute.value.params.id==='a'&&fixture.querySelector('textarea').value==='New body a','Navigation is blocked while an explicit note save is pending')
  finishOldSave();await wait(()=>!fixture.querySelector('textarea')?.disabled)
  await editorRouter.push('/notes/b');await wait(()=>fixture.querySelector('textarea')?.value==='Body b')
  assert(fixture.querySelector('textarea').value==='Body b'&&fixture.querySelector('.note-title-input').value==='Note b','Completed prior save does not replace the newly selected notebook')
  const longBody='z'.repeat(24000)+'IMPORTANT_TAIL'
  await setValue(fixture.querySelector('textarea'),longBody);await click('格式整理')
  await wait(()=>fixture.textContent.includes('不会截断正文'))
  assert(fixture.querySelector('textarea').value===longBody&&!button('用此结果替换正文'),'Oversized local tidy shows an error without altering or replacing the original notebook')
  app.unmount();window.fetch=priorFetch
  tokenStorage.clear(); authStore.state.user=null
  document.getElementById('results').textContent = `PASS ${passed.length}\n` + passed.join('\n')
  document.documentElement.dataset.result = 'passed'
} catch (error) {
  document.getElementById('results').textContent = `FAIL: ${error.stack}\nPassed:\n`+passed.join('\n')
  document.documentElement.dataset.result = 'failed'
}
