<script setup lang="ts">
import NoteDiff from './NoteDiff.vue'
import { ref } from 'vue'
import { researchApi } from '@/api/research'
const props=defineProps<{id:string;revision:number;body:string;dirty:boolean}>()
const emit=defineEmits<{restored:[]}>()
const open=ref(false),busy=ref(false),error=ref(''),page=ref(0)
const items=ref<Awaited<ReturnType<typeof researchApi.versions>>>([]),chosen=ref<Awaited<ReturnType<typeof researchApi.version>>|null>(null)
async function load(){busy.value=true;error.value='';try{items.value=await researchApi.versions(props.id,page.value)}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
async function select(id:string){busy.value=true;try{chosen.value=await researchApi.version(props.id,id)}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
async function restore(){if(!chosen.value||props.dirty||busy.value)return;busy.value=true;try{await researchApi.restoreVersion(props.id,chosen.value.id,props.revision);chosen.value=null;open.value=false;emit('restored')}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
</script>
<template>
 <section class="history-panel"><button class="button button--ghost" @click="open=!open;open&&load()">历史版本 {{ open?'−':'＋' }}</button>
 <div v-if="open" class="history-content"><p>查看旧版与当前正文，确认后恢复。当前版本也会保留；历史从本次功能上线后开始记录。</p><p v-if="error" role="alert">{{ error }}</p>
 <div class="history-layout"><nav aria-label="历史版本"><button v-for="v in items" :key="v.id" :disabled="busy" @click="select(v.id)">版本 {{ v.revision }} · {{ new Date(v.createdAt).toLocaleString() }}</button><p v-if="!items.length">暂无更早版本。</p><button :disabled="!page||busy" @click="page--;load()">上一页</button><button :disabled="items.length<30||busy" @click="page++;load()">下一页</button></nav>
 <div v-if="chosen"><h3>{{ chosen.title }}</h3><NoteDiff :before="chosen.body" :after="body" /><div class="history-diff"><section><strong>选中的旧版</strong><pre>{{ chosen.body }}</pre></section><section><strong>当前正文</strong><pre>{{ body }}</pre></section></div><p v-if="dirty">请先保存当前修改，再恢复旧版。</p><button class="button button--dark" :disabled="dirty||busy" @click="restore">确认恢复此版本</button></div></div></div></section>
</template>
<style scoped>
.history-panel{border-top:1px solid var(--line);padding:16px 0}.history-content>p{color:var(--muted);font-size:13px}.history-layout{display:grid;grid-template-columns:245px minmax(0,1fr);gap:24px}.history-layout nav button{display:block;background:none;border:0;padding:10px;text-align:left;color:var(--green);cursor:pointer}.history-diff{display:grid;grid-template-columns:1fr 1fr;gap:16px}.history-diff pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:380px;overflow:auto;background:var(--paper);border:1px solid var(--line);padding:16px;font-size:13px}
</style>
