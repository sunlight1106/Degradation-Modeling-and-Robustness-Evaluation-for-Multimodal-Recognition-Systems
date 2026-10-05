<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { renderNote } from '@/lib/markdown'
import { codeLanguages, highlightCode, normalizeLanguage } from '@/lib/codeBlocks'
const props = defineProps<{ body: string; format?: string }>()
const host = ref<HTMLElement | null>(null)
const html = computed(() => renderNote(props.body, props.format))
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
  await nextTick(); clear()
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
