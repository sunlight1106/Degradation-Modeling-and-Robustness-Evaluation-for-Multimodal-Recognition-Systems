<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { fetchBlob } from '@/api/client'
import { renderNote } from '@/lib/markdown'
import { codeLanguages, highlightCode, normalizeLanguage } from '@/lib/codeBlocks'
const props = defineProps<{ body: string; format?: string; noteId?:string }>()
const host = ref<HTMLElement | null>(null)
const html = computed(() => renderNote(props.body, props.format))
let imageEpoch=0, alive=true
const imageUrls=new Map<string,string>()
function clearImages(){imageUrls.forEach(URL.revokeObjectURL);imageUrls.clear()}
watch(()=>props.noteId,clearImages)
onBeforeUnmount(()=>{alive=false;imageEpoch++;clearImages()})
let active: Element[] = []
function clear() { active.forEach(node => node.classList.remove('code-symbol-active')); active = [] }
function highlight(event: Event) {
  clear()
  const target = event.target instanceof Element ? event.target.closest('.code-symbol') : null
  if (!target || (event.type === 'focusin' && !target.matches(':focus-visible'))) return
  const code = target.closest('pre')
  if (!code || !host.value?.contains(code)) return
  active = [...code.querySelectorAll('.code-symbol')].filter(node => node.getAttribute('data-symbol') === target.getAttribute('data-symbol'))
  active.forEach(node => node.classList.add('code-symbol-active'))
}
async function decorate() {
  const version=++imageEpoch
  await nextTick(); clear()
  const nodes=[...host.value?.querySelectorAll('[data-note-attachment]')||[]]
  const used=new Set(nodes.map(n=>n.getAttribute('data-note-attachment')))
  for(const [id,url] of imageUrls)if(!used.has(id)){URL.revokeObjectURL(url);imageUrls.delete(id)}
  async function display(node:Element,id:string){
    try {
      let url=imageUrls.get(id)
      if(!url){const blob=await fetchBlob(`/notes/${props.noteId}/attachments/${id}`);if(!alive||version!==imageEpoch)return;if(!['image/png','image/jpeg','image/webp'].includes(blob.type))return;url=URL.createObjectURL(blob);imageUrls.set(id,url)}
      const img=document.createElement('img');img.src=url;img.alt='笔记图片附件';img.style.maxWidth='100%';img.loading='lazy';node.replaceChildren(img)
    }catch{if(alive&&version===imageEpoch)node.textContent='图片不可用或尚未授权'}
  }
  let autoLoaded=0
  if(props.noteId) for(const node of nodes) {
    const id=node.getAttribute('data-note-attachment')||''
    if(!/^[a-f0-9-]{36}$/.test(id))continue
    if(autoLoaded++<12||imageUrls.has(id))await display(node,id)
    else {const button=document.createElement('button');button.type='button';button.textContent='加载这张图片';button.onclick=()=>{if(alive&&version===imageEpoch)void display(node,id)};node.replaceChildren(button)}
  }

  for (const code of host.value?.querySelectorAll('pre > code') || []) {
    const language = normalizeLanguage([...code.classList].find(name => name.startsWith('language-'))?.slice(9) || 'text')
    const source = code.textContent || ''
    // All document content was sanitized first; only escaped tokenizer output is inserted here.
    code.innerHTML = highlightCode(source, language)
    const pre = code.parentElement!
    pre.classList.add('note-code-block')
    pre.querySelector(':scope > .note-code-label')?.remove()
    const label = document.createElement('span'); label.className = 'note-code-label'
    label.textContent = (codeLanguages.find(item => item[0] === language)?.[1] || language) + (source.length > 30000 ? ' · 长代码以纯文本显示' : '')
    pre.prepend(label)
  }
}
watch(html, decorate)
onMounted(decorate)
</script>
<template><div ref="host" class="markdown-body" @pointerover="highlight" @pointerout="clear" @pointerleave="clear" @focusin="highlight" @focusout="clear" v-html="html" /></template>
