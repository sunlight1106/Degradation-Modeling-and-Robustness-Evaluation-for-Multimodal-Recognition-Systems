<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { VocabularyLesson } from '@/api/vocabulary'
import { api, ApiClientError } from '@/api/client'
import NotePersonalTools from './NotePersonalTools.vue'
const props = defineProps<{ lesson: VocabularyLesson }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null), busy = ref(false), error = ref(''), savedId = ref('')
let clientId = '', savedText = ''
const body = JSON.stringify({ term: props.lesson.term, ipa: props.lesson.ipa, meaning: props.lesson.meaning, collocations: props.lesson.collocations, example: props.lesson.example, usage: props.lesson.usageNote }, null, 2)
let alive = true
async function save(text: string) {
  if (busy.value) return
  if (savedText !== text) { savedText = text; clientId = crypto.randomUUID() }
  busy.value = true; error.value = ''; savedId.value = ''
  try { const note = await api.createNote({ clientId, title: `${props.lesson.term} · 学习笔记`, body: `# ${props.lesson.term}\n\n${props.lesson.ipa} · ${props.lesson.meaning}\n\n${text}`, library: '英语学习', tags: '词汇,AI辅助', contentFormat: 'MARKDOWN' }); if (alive) savedId.value = note.id }
  catch (reason) { if (alive) error.value = reason instanceof ApiClientError ? reason.message : '笔记保存失败，请重试' }
  finally { if (alive) busy.value = false }
}
onMounted(async () => { await nextTick(); if (!alive || !dialog.value) return; if (typeof dialog.value.showModal === 'function') dialog.value.showModal(); else dialog.value.setAttribute('open','') })
onBeforeUnmount(() => { alive = false; dialog.value?.close?.() })
</script>
<template><dialog ref="dialog" class="vocabulary-assistant" aria-labelledby="word-assistant-title" @cancel.prevent="!busy && emit('close')"><header><div><p class="page-kicker">WORD STUDIO</p><h3 id="word-assistant-title">{{ lesson.term }}</h3><p>讲解、练习与造句。这里的练习不计入记忆进度。</p></div><button class="table-action" aria-label="关闭单词助手" :disabled="busy" @click="emit('close')">关闭 ×</button></header><p v-if="error" class="inline-alert inline-alert--error" role="alert">{{ error }}</p><p v-if="savedId" role="status">已保存到英语学习笔记。<RouterLink :to="`/app/notes/${savedId}/edit`" @click="emit('close')">打开笔记 →</RouterLink></p><NotePersonalTools context-kind="vocabulary" :title="lesson.term" :body="body" :disabled="busy || !!savedId" @append="save" /></dialog></template>
<style scoped>.vocabulary-assistant{position:fixed;inset:0;margin:auto;width:min(850px,85vw);max-height:88vh;overflow:auto;background:var(--paper);color:var(--ink);border:1px solid var(--line);padding:30px 38px;box-shadow:0 24px 70px #0002}.vocabulary-assistant::backdrop{background:#1b293957}header{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;margin-bottom:24px}h3{font-size:30px;margin:6px 0}header p{font-size:13px;color:var(--muted)}:deep(.panel-header){padding-top:0}:deep(.assistant-instruction){margin:16px 0;display:block}:deep(.assistant-instruction textarea){width:100%;margin-top:8px}</style>
