<script setup lang="ts">
import {ref,onBeforeUnmount} from 'vue'
import {request} from '@/api/client'
import ReportButton from '@/components/ReportButton.vue'
const props=defineProps<{groupId:number;messageId:string;canRecall:boolean;canReport:boolean;recalled:boolean}>(),emit=defineEmits<{refresh:[]}>(),busy=ref(false),error=ref('');let active=true
onBeforeUnmount(()=>{active=false})
async function run(action:string){if(busy.value)return;busy.value=true;error.value='';try{await request(`/workspaces/${props.groupId}/messages/${props.messageId}/${action}`,{method:'POST',body:JSON.stringify({})});if(active){emit('refresh')}}catch(e){if(active)error.value=(e as Error).message}finally{if(active)busy.value=false}}
</script>
<template><div class="message-actions"><button v-if="canRecall&&!recalled" class="table-action" :disabled="busy" @click="run('recall')">撤回</button><ReportButton v-if="canReport&&!recalled" type="MESSAGE" :source-id="messageId" /><p v-if="error" role="alert" class="form-error">{{error}}</p></div></template>
<style scoped>.message-actions{display:flex;gap:14px;align-items:center;flex-wrap:wrap;font-size:12px}.message-actions form{display:flex;gap:10px;align-items:center;width:100%}.message-actions label{flex:1}</style>
