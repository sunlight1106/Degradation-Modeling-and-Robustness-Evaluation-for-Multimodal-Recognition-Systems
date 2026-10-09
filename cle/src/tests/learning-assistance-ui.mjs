// Synthetic transport only: no model, key, speech service, or learner progress is used.
import { createApp, nextTick, reactive } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import NotePersonalTools from '../components/NotePersonalTools.vue'
import WordPronunciation from '../components/WordPronunciation.vue'
import { authStore } from '../stores/auth.ts'
import { tokenStorage } from '../api/client.ts'
const fixture=document.getElementById('fixture'), passed=[]
const assert=(value,label)=>{if(!value)throw new Error(label);passed.push(label)}
const wait=async predicate=>{for(let i=0;i<100;i++){await new Promise(r=>setTimeout(r,5));await nextTick();if(predicate())return}throw new Error('UI timed out')}
const button=label=>[...fixture.querySelectorAll('button')].find(b=>b.textContent.includes(label))
const envelope=data=>new Response(JSON.stringify({success:true,data}),{headers:{'Content-Type':'application/json'}})
const previews=[],executions=[],applied=[]
let action, app
window.fetch=async(url,init={})=>{
 const path=String(url)
 if(path.endsWith('/providers'))return envelope([{provider:'QWEN',remoteEnabled:true}])
 if(path.endsWith('/settings'))return envelope([{provider:'QWEN',model:'synthetic',enabled:true,configured:true}])
 if(path.endsWith('/preview')){const body=JSON.parse(init.body);previews.push(body);action=body.action;return envelope({previewToken:'synthetic',provider:'QWEN',model:'synthetic',action,context:body.body,systemPrompt:'test',outboundBytes:200,expiresAt:new Date(Date.now()+300000).toISOString()})}
 if(path.endsWith('/execute')){executions.push(init.body);return envelope({action,engine:'PERSONAL_AI:QWEN',result:'Corrected: interested in research.',items:[],persistenceStatus:'SAVED'})}
 throw new Error(`Unexpected request: ${path}`)
}
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]})
try{
 tokenStorage.set('synthetic-learning-assistance')
 authStore.state.user={id:19,permissions:['personal-ai:use','vocabulary:use','note:write']}
 await router.push('/app/vocabulary');await router.isReady()
 const props=reactive({title:'work',body:'term: work',contextKind:'vocabulary',onAppend:text=>applied.push(text)})
 app=createApp(NotePersonalTools,props);app.use(router);app.mount(fixture)
 await wait(()=>button('我的造句纠错')&&!button('我的造句纠错').disabled)
 assert(!fixture.textContent.includes('选择实验结果'),'Vocabulary assistant has no experiment-source controls')
 assert(!button('摘要')&&!!button('讲解与搭配')&&!!button('生成练习'),'Word-specific actions are separate from notebook tools')
 button('我的造句纠错').click();await nextTick()
 assert(previews.length===0&&fixture.textContent.includes('先在下方写一句'),'Sentence correction requires the learner sentence')
 const input=fixture.querySelector('.assistant-instruction textarea');input.value='I am interested on research.';input.dispatchEvent(new Event('input',{bubbles:true}));await nextTick()
 button('我的造句纠错').click();await wait(()=>fixture.querySelector('[aria-label="发送前确认"]'))
 assert(previews[0].action==='word-correct'&&previews[0].body.includes(input.value),'Exact word context and learner sentence are previewed')
 assert(previews[0].selectedTaskIds.length===0,'Word actions send no experiment resources')
 assert(executions.length===0&&button('确认发送并生成').disabled,'Opening an AI preview makes no provider request')
 input.value='I work on this project.';input.dispatchEvent(new Event('input',{bubbles:true}));await nextTick()
 assert(!fixture.querySelector('[aria-label="发送前确认"]'),'Editing the sentence invalidates prior approval')
 button('生成练习').click();await wait(()=>fixture.querySelector('[aria-label="发送前确认"]'))
 const consent=fixture.querySelector('[aria-label="发送前确认"] input[type=checkbox]');consent.checked=true;consent.dispatchEvent(new Event('change',{bubbles:true}));await nextTick()
 button('确认发送并生成').click();await wait(()=>fixture.querySelector('[aria-label="生成结果预览"]'))
 assert(executions.length===1&&applied.length===0,'AI exercises remain unapplied and do not change progress')
 button('保存到英语笔记').click();await nextTick()
 assert(applied.length===1&&applied[0].includes('Corrected:'),'Saving to notes is a separate manual action')
 assert(!!fixture.querySelector('[aria-label="生成结果预览"]'),'Generated text remains available if saving needs a retry')
 authStore.state.user={id:20,permissions:['personal-ai:use','vocabulary:use']};await nextTick();await wait(()=>!button('生成练习').disabled)
 assert(!fixture.querySelector('[aria-label="生成结果预览"]'),'Switching accounts clears the previous generated text')
 button('讲解与搭配').click();await wait(()=>fixture.querySelector('[aria-label="发送前确认"]'))
 const check=fixture.querySelector('[aria-label="发送前确认"] input[type=checkbox]');check.checked=true;check.dispatchEvent(new Event('change',{bubbles:true}));await nextTick();button('确认发送并生成').click();await wait(()=>fixture.querySelector('[aria-label="生成结果预览"]'))
 assert(button('保存到英语笔记').disabled,'Vocabulary-only users can read help without gaining notebook write access')
 app.unmount();fixture.innerHTML=''
 authStore.state.user={id:21,permissions:['personal-ai:use','note:write']}
 app=createApp(NotePersonalTools,{title:'Test',body:'Notebook content'});app.use(router);app.mount(fixture);await wait(()=>fixture.querySelector('select option[value=QWEN]'))
 assert(!!button('解释概念')&&!!button('润色')&&!!button('生成自测'),'Notebook has explanation, polishing and self-test tools')
 assert(button('解释概念').disabled,'Remote-only actions cannot masquerade as local rules')
 app.unmount();fixture.innerHTML=''
 const spoken=[];window.speechSynthesis={cancel(){},getVoices:()=>[{lang:'en-GB'}],speak(value){spoken.push(value)}}
 globalThis.SpeechSynthesisUtterance=class{constructor(text){this.text=text}}
 window.SpeechSynthesisUtterance=globalThis.SpeechSynthesisUtterance
 app=createApp(WordPronunciation,{text:'work'});app.mount(fixture)
 const accent=fixture.querySelector('select');accent.value='en-GB';accent.dispatchEvent(new Event('change',{bubbles:true}));const slow=fixture.querySelector('input');slow.checked=true;slow.dispatchEvent(new Event('change',{bubbles:true}));await nextTick();button('听读').click()
 assert(spoken[0].text==='work'&&spoken[0].lang==='en-GB'&&spoken[0].rate===.65,'British and slow pronunciation use the chosen settings')
 spoken[0].onerror({error:'voice-unavailable'});await nextTick()
 assert(fixture.textContent.includes('检查系统'),'Speech errors have a useful fallback')
 document.documentElement.dataset.result='passed';document.getElementById('results').textContent=`PASS ${passed.length}\n${passed.join('\n')}`
}catch(error){document.documentElement.dataset.result='failed';document.getElementById('results').textContent=`FAIL after ${passed.length}: ${error.stack}`}
finally{app?.unmount()}
