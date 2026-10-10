import {createApp,h,nextTick,ref} from 'vue'
import VocabularyRecallCard from '../components/VocabularyRecallCard.vue'
import PersonalAiDiagnostics from '../components/PersonalAiDiagnostics.vue'
import GroupInvitations from '../components/GroupInvitations.vue'
import {diffLines,mergeDraft} from '../lib/noteDiff'
import {renderNote} from '../lib/markdown'
import {tokenStorage} from '../api/client'
const fixture=document.getElementById('fixture'),passed=[]
const assert=(ok,label)=>{if(!ok)throw Error(label);passed.push(label)}
const wait=async test=>{for(let i=0;i<100;i++){await new Promise(r=>setTimeout(r,5));await nextTick();if(test())return}throw Error('Timed out')}
const button=text=>[...fixture.querySelectorAll('button')].find(b=>b.textContent.includes(text))
const envelope=data=>new Response(JSON.stringify({success:true,data}),{headers:{'Content-Type':'application/json'}})
let app;const requests=[]
try {
 tokenStorage.set('synthetic-completion-session')
 assert(mergeDraft('a\nb\nc','A\nb\nc','a\nb\nC')==='A\nb\nC','Disjoint edits merge without losing either change')
 assert(mergeDraft('a\nb','a\nB','a\nother')===null,'Overlapping edits require manual review')
 assert(diffLines('a\nb','a\nB').some(l=>l.kind==='remove'&&l.text==='b'),'Version diff marks removed text')
 assert(diffLines('a\nb','a\nB').some(l=>l.kind==='add'&&l.text==='B'),'Version diff marks added text')
 assert(!renderNote('![x](https://example.invalid/track.png)<script>alert(1)</script>').includes('<img'),'Remote images and scripts remain blocked')
 const img='10000000-0000-0000-0000-000000000001';assert(renderNote(`![image](attachment:${img})`).includes('data-note-attachment'),'Owned attachment markers survive sanitization without a remote image URL')
 window.fetch=async(url,init={})=>{requests.push({url:String(url),body:init.body});if(String(url).includes('/diagnostics/preview'))return envelope({token:'synthetic-consent',model:'synthetic-model',endpoint:'https://example.invalid',request:'Fixed short request only'});if(String(url).includes('/diagnostics/execute'))return envelope({success:true,code:'CONNECTED',message:'Connected synthetic model',milliseconds:10,models:[],usageStatus:'SAVED'});if(String(url).endsWith('/workspaces/invitations'))return envelope([{id:'synthetic-invite',groupId:1,groupName:'Synthetic group',role:'MEMBER'}]);if(String(url).includes('/decision'))return envelope(null);throw Error('Unexpected request')}
 app=createApp({render:()=>h(VocabularyRecallCard,{question:{id:'listening',term:'work',ipa:'/wɜːk/',practiceKind:'LISTENING',introduced:false,hintLevel:0,prompt:'听一听，写下你听到的单词'},answer:null,busy:false})});app.mount(fixture);await nextTick()
 assert(!fixture.textContent.includes('work')&&!fixture.textContent.includes('wɜːk'),'Dictation does not reveal the word or its phonetics')
 assert(fixture.querySelector('[aria-label="播放本题语音"]'),'Dictation accessible label does not reveal the answer')
 assert(!requests.length,'Dictation does not request a lesson before answering');app.unmount();fixture.replaceChildren()
 app=createApp({render:()=>h(PersonalAiDiagnostics,{setting:{provider:'DEEPSEEK',revision:1,configured:true,enabled:true},disabled:false})});app.mount(fixture);button('测试当前模型').click();await wait(()=>fixture.textContent.includes('Fixed short request'))
 assert(!requests.some(r=>r.url.endsWith('/execute')),'Connection test only previews before explicit confirmation')
 assert(button('确认检查').disabled,'Sending remains disabled without confirmation')
 const consent=fixture.querySelector('input[type=checkbox]');consent.checked=true;consent.dispatchEvent(new Event('change',{bubbles:true}));await nextTick();button('确认检查').click();await wait(()=>fixture.textContent.includes('Connected synthetic'))
 const outbound=JSON.parse(requests.find(r=>r.url.endsWith('/execute')).body);assert(outbound.confirmed&&outbound.token==='synthetic-consent'&&Object.keys(outbound).length===2,'Execution submits only the one-use consent token')
 app.unmount();fixture.replaceChildren();requests.length=0
 app=createApp({render:()=>h(GroupInvitations)});app.mount(fixture);await wait(()=>fixture.textContent.includes('Synthetic group'))
 assert(!requests.some(r=>r.url.includes('/decision')),'Displaying an invitation does not silently join the group')
 button('拒绝').click();await wait(()=>requests.some(r=>r.url.includes('/decision')));assert(JSON.parse(requests.find(r=>r.url.includes('/decision')).body).accept===false,'Rejecting an invitation sends an explicit negative decision')
}finally{app?.unmount();fixture.replaceChildren();tokenStorage.clear()}
document.getElementById('results').textContent='PASS '+passed.length+' assertions\n'+passed.join('\n')
document.documentElement.dataset.result='passed'
