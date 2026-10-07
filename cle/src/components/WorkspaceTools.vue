<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { tokenStorage } from '@/api/client'
import { researchApi,type Hit } from '@/api/research'
import { authStore } from '@/stores/auth'
const router=useRouter(), dialog=ref<HTMLDialogElement|null>(null), searchInput=ref<HTMLInputElement|null>(null)
const query=ref(''),hits=ref<Hit[]>([]),error=ref(''),loading=ref(false),notifications=ref<Awaited<ReturnType<typeof researchApi.notifications>>>([])
let debounce:ReturnType<typeof setTimeout>|undefined, retry:ReturnType<typeof setTimeout>|undefined, poll:ReturnType<typeof setInterval>|undefined,stream:AbortController|undefined,seq=0,disposed=false
async function open(){dialog.value?.showModal();await nextTick();searchInput.value?.focus();void search()}
function key(e:KeyboardEvent){if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();void open()}}
async function search(){const n=++seq;loading.value=true;error.value='';try{const result=await researchApi.search({q:query.value,page:'0'});if(n===seq)hits.value=result.items}catch(e){if(n===seq)error.value=(e as Error).message}finally{if(n===seq)loading.value=false}}
function go(path:string){dialog.value?.close();void router.push(path)}
watch(query,()=>{seq++;clearTimeout(debounce);debounce=setTimeout(search,250)})
async function refresh(){try{const rows=await researchApi.notifications();if(!disposed)notifications.value=rows}catch{/* The normal request client handles session expiry. */}}
async function connect(){
 if(disposed||!authStore.state.user)return
 const generation=tokenStorage.generation();stream=new AbortController()
 try{
  const response=await fetch((import.meta.env.VITE_API_BASE_URL||'/api/v1')+'/notifications/events',{headers:{Authorization:`Bearer ${tokenStorage.get()}`},signal:stream.signal,cache:'no-store'})
  if(!response.ok||!response.body)throw new Error('Live updates unavailable')
  const reader=response.body.getReader(),decoder=new TextDecoder();let buffer=''
  while(!disposed&&generation===tokenStorage.generation()){
   const {done,value}=await reader.read();if(done)break;buffer+=decoder.decode(value,{stream:true}).replace(/\r\n/g,'\n')
   let end:number;while((end=buffer.indexOf('\n\n'))>=0){const event=buffer.slice(0,end);buffer=buffer.slice(end+2);if(event.includes('event:refresh')){void refresh();window.dispatchEvent(new Event('pkb:live-update'))}}
  }
  await reader.cancel()
 }catch{/* Retry on a bounded delay. The 30-second refresh also covers a missed reconnect event. */}
 finally{if(!disposed)retry=setTimeout(connect,2000)}
}
onMounted(()=>{window.addEventListener('keydown',key);void refresh();void connect();poll=setInterval(refresh,30000)})
onBeforeUnmount(()=>{disposed=true;seq++;stream?.abort();clearTimeout(debounce);clearTimeout(retry);clearInterval(poll);window.removeEventListener('keydown',key)})
</script>
<template>
 <button class="workspace-search-button" @click="open">搜索 <kbd>Ctrl K</kbd></button>
 <details class="notification-menu"><summary>通知 <span v-if="notifications.length">{{ notifications.reduce((sum,n)=>sum+n.count,0) }}</span></summary><div class="notification-list"><strong>通知中心</strong><p v-if="!notifications.length">暂时没有未读消息</p><button v-for="n in notifications" :key="n.id" @click="go(n.url)">{{ n.title }} <small>{{ n.count }}</small></button><RouterLink to="/app/research?tab=cards">查看到期复习卡 →</RouterLink></div></details>
 <dialog ref="dialog" class="command-dialog" @click="e=>{if(e.target===dialog)dialog?.close()}"><div class="command-head"><input ref="searchInput" v-model="query" placeholder="搜索笔记、资料、图片识别结果…" aria-label="全局搜索"/><button @click="dialog?.close()" aria-label="关闭搜索">Esc</button></div><p v-if="error" role="alert">{{ error }}</p><p v-if="loading">正在搜索…</p><div class="command-results"><button v-for="h in hits" :key="h.kind+h.id" @click="go('/app/research?tab=search&type='+h.kind+'&source='+h.id)"><small>{{ h.kind }}</small><strong>{{ h.title }}</strong><span>{{ h.excerpt }}</span></button><p v-if="!loading&&!hits.length">没有找到可访问的资料。</p></div><footer><button @click="go('/app/research?tab=search&q='+encodeURIComponent(query))">全部结果与筛选 →</button></footer></dialog>
</template>
<style scoped>
.workspace-search-button,.notification-menu summary{background:none;border:0;color:var(--ink);font-size:13px;padding:8px 12px;cursor:pointer}.workspace-search-button kbd{color:var(--muted);margin-left:10px;font-size:11px}.notification-menu{position:relative}.notification-list{position:absolute;right:0;top:40px;z-index:80;width:320px;max-height:450px;overflow:auto;background:var(--paper);border:1px solid var(--line);padding:20px;box-shadow:0 12px 30px #20304015}.notification-list button{display:flex;width:100%;padding:12px 0;border:0;border-bottom:1px solid var(--line);background:none;text-align:left;justify-content:space-between;cursor:pointer;color:var(--ink)}.notification-list p,.notification-list a{font-size:13px;color:var(--muted)}.command-dialog{width:min(720px,90vw);margin:12vh auto;border:1px solid var(--line);border-radius:6px;padding:0;color:var(--ink);background:var(--paper);box-shadow:0 28px 90px #15202c25}.command-dialog::backdrop{background:#14223355}.command-head{display:flex;gap:16px;padding:20px;border-bottom:1px solid var(--line)}.command-head input{flex:1;border:0;outline:0;background:none;color:inherit;font-size:17px}.command-head button,.command-dialog footer button{background:none;border:0;color:var(--green);cursor:pointer}.command-results{max-height:52vh;overflow:auto}.command-results>button{display:grid;grid-template-columns:70px 1fr;gap:5px 12px;text-align:left;width:100%;border:0;background:none;padding:16px 24px;border-bottom:1px solid var(--line);color:var(--ink);cursor:pointer}.command-results>button:hover,.command-results>button:focus-visible{background:var(--code-paper)}.command-results small{grid-row:span 2;color:var(--muted);font-size:10px}.command-results span{font-size:13px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.command-results p,.command-dialog>p{padding:20px}.command-dialog footer{padding:14px 24px}
</style>
