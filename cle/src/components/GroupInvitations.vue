<script setup lang="ts">
import {onMounted,onBeforeUnmount,ref} from 'vue'
import {request} from '@/api/client'
const emit=defineEmits<{refresh:[]}>(), items=ref<{id:string;groupId:number;groupName:string;role:string}[]>([]),groupId=ref(''),busy=ref(false),error=ref(''),message=ref('');let active=true
onBeforeUnmount(()=>{active=false})
async function load(){try{const r=await request<typeof items.value>('/workspaces/invitations');if(active)items.value=r}catch(e){if(active)error.value=(e as Error).message}}
onMounted(load)
async function run(path:string,body:unknown){if(busy.value)return;busy.value=true;error.value='';message.value='';try{await request(path,{method:'POST',body:JSON.stringify(body)});if(active){await load();emit('refresh');message.value='操作已完成'}}catch(e){if(active)error.value=(e as Error).message}finally{if(active)busy.value=false}}
</script>
<template><details class="invitation-panel"><summary>邀请与加入申请 <span v-if="items.length">· {{items.length}} 个待确认</span></summary><p v-if="error" class="form-error" role="alert">{{error}}</p><p v-if="message" role="status">{{message}}</p><div v-for="i in items" :key="i.id" class="invite-row"><strong>{{i.groupName}}</strong><span>{{i.role==='VIEWER'?'访客':'成员'}}</span><button class="table-action" :disabled="busy" @click="run('/workspaces/invitations/'+i.id+'/decision',{accept:true})">接受</button><button class="table-action" :disabled="busy" @click="run('/workspaces/invitations/'+i.id+'/decision',{accept:false})">拒绝</button></div><form @submit.prevent="run('/workspaces/'+Number(groupId)+'/join-request',{})"><label>群组编号<input v-model="groupId" class="field-input" type="number" min="1" required placeholder="向群主索取编号" /></label><button class="button button--ghost" :disabled="busy||!groupId">申请加入</button></form><small>只可申请加入已开放申请的群组。邀请需要你确认后才会加入。</small></details></template>
<style scoped>.invitation-panel{padding:16px 0;border-bottom:1px solid var(--line);font-size:13px}summary{cursor:pointer;color:var(--green)}form,.invite-row{display:flex;align-items:center;gap:16px;padding:14px 0}label{display:flex;align-items:center;gap:12px}.invite-row strong{flex:1}small{color:var(--muted)}</style>
