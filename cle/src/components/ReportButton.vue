<script setup lang="ts">
import {ref,watch,onBeforeUnmount} from 'vue'
import {RouterLink} from 'vue-router'
import {safetyApi} from '@/api/moderation'
const props=defineProps<{type:'MESSAGE'|'CHAT';sourceId:string|number}>()
const open=ref(false),reason=ref(''),busy=ref(false),error=ref(''),sent=ref(false)
let epoch=0
watch(()=>[props.type,props.sourceId],()=>{epoch++;open.value=false;reason.value='';error.value='';sent.value=false;busy.value=false})
onBeforeUnmount(()=>epoch++)
async function send(){
 if(busy.value||!reason.value.trim())return
 const version=epoch;busy.value=true;error.value=''
 try{await safetyApi.post('reports',{type:props.type,sourceId:String(props.sourceId),reason:reason.value});if(version===epoch){sent.value=true;open.value=false;reason.value=''}}
 catch(e){if(version===epoch)error.value=(e as Error).message}finally{if(version===epoch)busy.value=false}
}
</script>
<template><div class="report-control"><button v-if="!sent" class="table-action" :aria-expanded="open" :disabled="busy" @click="open=!open">举报</button><RouterLink v-else to="/app/safety" class="report-sent">已举报 · 查看进度</RouterLink><form v-if="open" class="report-form" @submit.prevent="send"><label>举报原因<textarea v-model="reason" required maxlength="500" rows="3" placeholder="说明这条消息存在的问题，审核员会查看消息原文。" :disabled="busy" /></label><footer><small>举报人身份不会向被举报人公开。</small><button type="button" class="table-action" :disabled="busy" @click="open=false">取消</button><button class="button button--dark button--small" :disabled="busy||!reason.trim()">{{busy?'提交中…':'提交举报'}}</button></footer></form><p v-if="error" role="alert" class="form-error">{{error}}</p></div></template>
<style scoped>.report-control{font-size:12px;margin:8px 0}.report-sent{color:var(--muted);text-decoration:none}.report-form{padding:16px;border:1px solid var(--line);border-radius:5px;background:var(--paper);max-width:620px;text-align:left}.report-form label{display:block;color:var(--ink)}textarea{display:block;width:100%;margin:10px 0;padding:10px;background:var(--paper);color:var(--ink);border:1px solid var(--line);font:inherit;resize:vertical}footer{display:flex;align-items:center;gap:12px}small{flex:1;color:var(--muted)}</style>
