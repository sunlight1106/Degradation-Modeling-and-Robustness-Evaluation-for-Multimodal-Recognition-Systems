import { createApp,nextTick } from 'vue'
import { createRouter,createMemoryHistory } from 'vue-router'
import { researchApi } from '../api/research'
import { personalApi } from '../api/personal'
import { authStore } from '../stores/auth'
import ResearchView from '../views/ResearchView.vue'
import WorkspaceTools from '../components/WorkspaceTools.vue'
import SearchHighlight from '../components/SearchHighlight.vue'
const fixture=document.getElementById('fixture'),checks=[]
const assert=(value,message)=>{if(!value)throw new Error(message);checks.push(message)}
const wait=async condition=>{for(let i=0;i<150;i++){await new Promise(r=>setTimeout(r,5));await nextTick();if(condition())return}throw new Error('UI timed out')}
const button=text=>[...fixture.querySelectorAll('button')].find(b=>b.textContent.includes(text))
const input=async(element,value)=>{element.value=value;element.dispatchEvent(new Event('input',{bubbles:true}));await nextTick()}
const original={...researchApi},oldSettings=personalApi.settings
const hit={id:'one',kind:'NOTE',title:'Alpha notes',excerpt:'private alpha example',url:'/app/notes/one/edit',updatedAt:'2026-10-10T00:00:00Z'}
const filters={q:'alpha',type:'NOTE',tag:'',since:null,group:null,sort:'recent'}
let app,bookmarked=0,searches=[],resolveOld,oldSignal
try{
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});await router.push('/app/research');await router.isReady()
 authStore.state.user={id:1,roleCode:'ADMIN',permissions:['research:use','note:read','note:write','personal-ai:use']};personalApi.settings=async()=>[]
 researchApi.search=async params=>{searches.push(params);return {items:[hit],hasMore:false,page:0}}
 researchApi.savedSearches=async()=>[{id:'saved',name:'Reading',filters}]
 researchApi.bookmarkKeys=async()=>[];researchApi.bookmarks=async()=>({items:[hit],hasMore:false,page:0})
 researchApi.bookmark=async()=>{bookmarked++};researchApi.removeBookmark=async()=>{bookmarked--}
 researchApi.source=async()=>({...hit,body:'Latest source body'})
 researchApi.saveSearch=async(name,filters)=>({id:'new',name,filters});researchApi.removeSearch=async()=>{}
 app=createApp(ResearchView);app.use(router);app.mount(fixture);await wait(()=>button('Alpha notes'))
 const bookmark=fixture.querySelector('.bookmark-button');bookmark.click();await wait(()=>bookmarked===1&&bookmark.getAttribute('aria-pressed')==='true')
 assert(bookmark.textContent==='已收藏','Bookmark updates after a successful server save')
 button('Reading').click();await wait(()=>searches.some(p=>p.q==='alpha'&&p.sort==='recent'))
 assert(fixture.querySelector('select[aria-label="搜索排序"]').value==='recent','Saved search restores its actual sorting and filters')
 assert(fixture.querySelectorAll('mark').length>=2,'Matches in titles and excerpts are highlighted')
 button('Alpha notes').click();await wait(()=>fixture.querySelector('.source-reader'));assert(fixture.querySelector('.source-reader').textContent.includes('Latest source body'),'Opening a bookmark reads current source content')
 const save=fixture.querySelector('.save-search-form');save.open=true;await input(save.querySelector('input'),'My filter');save.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await wait(()=>button('My filter'));assert(!!button('My filter'),'Current search can be named and saved')
 assert(!save.open,'Successful save closes the small editor without hiding the saved search')
 app.unmount()
 app=createApp(SearchHighlight,{text:'<img src=x> Java[] a+b',query:'Java[] a+b'});app.mount(fixture)
 assert(fixture.querySelectorAll('mark').length===2&&!fixture.querySelector('img'),'Highlight treats regex symbols and HTML as literal text');app.unmount()
 const proto=Object.getPrototypeOf(document.createElement('dialog'));proto.showModal=function(){this.setAttribute('open','')};proto.close=function(){this.removeAttribute('open');this.dispatchEvent(new Event('close'))}
 window.fetch=async()=>{throw new Error('Synthetic disconnected stream')};researchApi.notifications=async()=>[]
 searches=[];researchApi.search=async(params,signal)=>{searches.push(params.q);if(params.q==='older'){oldSignal=signal;return new Promise(resolve=>{resolveOld=resolve})}return {items:[{...hit,title:params.q||'Recent notes'}],hasMore:false,page:0}}
 app=createApp(WorkspaceTools);app.use(router);app.mount(fixture);button('搜索').click();await wait(()=>fixture.querySelector('[role="option"]'))
 const field=fixture.querySelector('[role="combobox"]');await input(field,'older');await wait(()=>resolveOld)
 await input(field,'a');await input(field,'al');await input(field,'alpha');await wait(()=>fixture.querySelector('[role="option"] strong')?.textContent==='alpha')
 assert(oldSignal.aborted,'A superseded search aborts its transport')
 assert(!searches.includes('a')&&!searches.includes('al'),'Rapid typing is debounced into one query')
 resolveOld({items:[{...hit,title:'Stale result'}],hasMore:false,page:0});await nextTick();await nextTick()
 assert(!fixture.textContent.includes('Stale result'),'An old response cannot overwrite the latest search')
 field.dispatchEvent(new window.KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true}));await nextTick()
 assert(field.getAttribute('aria-activedescendant')==='command-hit-0','Keyboard selection exposes the selected result to assistive technology')
 field.dispatchEvent(new window.KeyboardEvent('keydown',{key:'Enter',bubbles:true}));await wait(()=>router.currentRoute.value.query.source==='one');assert(!fixture.querySelector('dialog').open,'Enter opens the result and closes the search')
 button('搜索').click();await wait(()=>fixture.querySelector('dialog').open);field.dispatchEvent(new window.KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true}));await nextTick();assert(!fixture.querySelector('dialog').open,'Escape closes search')
 app.unmount();authStore.state.user={id:2,roleCode:'USER',permissions:[]};app=createApp(WorkspaceTools);app.use(router);app.mount(fixture);await nextTick();assert(!fixture.querySelector('.workspace-search-button'),'Search entry respects the account module permission')
 document.getElementById('results').textContent=`PASS (${checks.length} assertions)\n`+checks.join('\n');document.documentElement.dataset.result='passed'
}catch(error){document.getElementById('results').textContent=error.stack;document.documentElement.dataset.result='failed'}finally{app?.unmount();Object.assign(researchApi,original);personalApi.settings=oldSettings}
