<script setup lang="ts">
import {ref,onBeforeUnmount} from 'vue'
import {request} from '@/api/client'
const props=defineProps<{groupId:number;messageId:string;canRecall:boolean;recalled:boolean}>(),emit=defineEmits<{refresh:[]}>(),reporting=ref(false),reason=ref(''),busy=ref(false),error=ref('');let active=true
onBeforeUnmount(()=>{active=false})
async function run(action:string){if(busy.value)return;busy.value=true;error.value='';try{await request(`/workspaces/${props.groupId}/messages/${props.messageId}/${action}`,{method:'POST',body:JSON.stringify(action==='report'?{reason:reason.value}:{})});if(active){reporting.value=false;reason.value='';emit('refresh')}}catch(e){if(active)error.value=(e as Error).message}finally{if(active)busy.value=false}}
</script>
<template><div class="message-actions"><button v-if="canRecall&&!recalled" class="table-action" :disabled="busy" @click="run('recall')">撤回</button><button class="table-action" :disabled="busy" @click="reporting=!reporting">举报</button><form v-if="reporting" @submit.prevent="run('report')"><label>举报原因<input v-model="reason" class="field-input" required maxlength="500" /></label><button class="table-action" :disabled="busy||!reason.trim()">提交</button></form><p v-if="error" role="alert" class="form-error">{{error}}</p></div></template>
<style scoped>.message-actions{display:flex;gap:14px;align-items:center;flex-wrap:wrap;font-size:12px}.message-actions form{display:flex;gap:10px;align-items:center;width:100%}.message-actions label{flex:1}</style>
