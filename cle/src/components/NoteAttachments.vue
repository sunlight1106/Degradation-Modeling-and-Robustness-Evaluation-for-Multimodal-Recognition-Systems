<script setup lang="ts">
import {onMounted,onBeforeUnmount,ref} from 'vue'
import {request} from '@/api/client'
const props=defineProps<{id:string;disabled:boolean}>(),emit=defineEmits<{insert:[string];refresh:[]}>(),busy=ref(false),error=ref('')
let active=true
onMounted(()=>document.addEventListener('paste',paste))
onBeforeUnmount(()=>{active=false;document.removeEventListener('paste',paste)})
function paste(e:ClipboardEvent){const node=e.target;if(!(node instanceof HTMLTextAreaElement)||props.disabled)return;const f=Array.from(e.clipboardData?.files||[]).find(f=>f.type.startsWith('image/'));if(f){e.preventDefault();void upload(f)}}
async function upload(file:File){if(busy.value||props.disabled)return;if(!['image/png','image/jpeg','image/webp'].includes(file.type)||file.size>20*1024*1024){error.value='请选择不超过 20 MB 的 PNG、JPEG 或 WEBP 图片';return}busy.value=true;error.value='';try{const form=new FormData();form.set('file',file);const r=await request<{id:string;name:string}>(`/notes/${props.id}/attachments`,{method:'POST',body:form});if(active){emit('insert',`\n![图片附件](attachment:${r.id})\n`);emit('refresh')}}catch(e){if(active)error.value=(e as Error).message}finally{if(active)busy.value=false}}
function choose(e:Event){const input=e.target as HTMLInputElement;const f=input.files?.[0];if(f)void upload(f);input.value=''}
</script>
<template><div class="note-upload"><label class="button button--ghost button--small">{{busy?'图片上传中…':'添加图片'}}<input type="file" accept="image/png,image/jpeg,image/webp" :disabled="disabled||busy" @change="choose" /></label><span>可直接将图片粘贴到正文；仅保存在当前账号。</span><p v-if="error" class="form-error" role="alert">{{error}}</p></div></template>
<style scoped>.note-upload{display:flex;align-items:center;gap:14px;flex-wrap:wrap;font-size:12px;color:var(--muted);padding:12px 0}.note-upload input{position:absolute;width:1px;height:1px;opacity:0}.note-upload label:focus-within{outline:2px solid var(--green)}</style>
