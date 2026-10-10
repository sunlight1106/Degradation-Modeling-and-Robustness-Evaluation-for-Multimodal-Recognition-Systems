<script setup lang="ts">
import {onMounted,onBeforeUnmount,ref} from 'vue'
import {api} from '@/api/client'
import type {NoteView} from '@/types/api'
import {mergeDraft} from '@/lib/noteDiff'
import NoteDiff from './NoteDiff.vue'
const props=defineProps<{id:string;ours:string;base:string}>(),emit=defineEmits<{merged:[{body:string;revision:number;sourceBody:string}];close:[]}>(),server=ref<NoteView>(),merged=ref(props.ours),error=ref(''),message=ref('');let active=true;const sourceBody=props.ours
onBeforeUnmount(()=>{active=false})
onMounted(async()=>{try{const note=await api.note(props.id);if(!active)return;server.value=note;const result=mergeDraft(props.base,props.ours,note.body);if(result!==null){merged.value=result;message.value='两处改动没有重叠，已生成合并建议，请检查后应用。'}else message.value='两处改动有重叠，请结合左右内容编辑合并结果。'}catch(e){if(active)error.value=(e as Error).message}})
</script>
<template><section class="conflict-panel"><header><h3>合并笔记改动</h3><button class="table-action" @click="emit('close')">收起</button></header><p v-if="error" role="alert" class="form-error">{{error}}</p><template v-if="server"><p>{{message}} 应用后会保存为新版本；服务器再次更新时仍会阻止覆盖。</p><NoteDiff :before="server.body" :after="ours" /><div class="conflict-sources"><details><summary>本地完整草稿</summary><pre>{{ours}}</pre></details><details><summary>服务器完整版本 · {{server.revision}}</summary><pre>{{server.body}}</pre></details></div><label class="field-label">编辑合并结果<textarea v-model="merged" class="field-input" rows="12" maxlength="200000" /></label><button class="button button--dark" :disabled="!merged.trim()" @click="emit('merged',{body:merged,revision:server.revision??0,sourceBody})">应用合并并保存</button></template></section></template>
<style scoped>.conflict-panel{padding:24px;border:1px solid var(--line);background:var(--paper)}header{display:flex;justify-content:space-between}.conflict-sources{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin:16px 0}summary{cursor:pointer;color:var(--green)}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:240px;overflow:auto}p{font-size:13px;line-height:1.8}</style>
