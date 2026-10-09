<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { vocabularyApi } from '@/api/vocabulary'
import type { VocabularyAnswer, VocabularyLesson, VocabularyQuestion } from '@/api/vocabulary'
import VocabularyLessonCard from './VocabularyLessonCard.vue'
const props = defineProps<{ question: VocabularyQuestion; answer: VocabularyAnswer | null; busy: boolean }>()
const emit = defineEmits<{ submit: [text: string]; skip: []; next: [] }>()
const lesson = ref<VocabularyLesson | null>(null), phase = ref<'introduce' | 'recall'>('recall')
const text = ref(''), hint = ref(''), hintLevel = ref(0), pending = ref(false), error = ref('')
const input = ref<HTMLInputElement | null>(null), continueButton = ref<HTMLButtonElement | null>(null)
const evidenceNames: Record<string, string> = { DELAYED: '延迟独立回忆', IMMEDIATE: '刚看过后的即时回忆', PROMPTED: '提示后答对', INCORRECT: '本次待巩固' }
let active = true, generation = 0
async function retryLesson() {
  if (pending.value) return
  pending.value = true; error.value = ''; const version = generation
  try { const value = await vocabularyApi.lesson(props.question.id); if (active && version === generation) lesson.value = value }
  catch (reason) { if (active && version === generation) error.value = reason instanceof Error ? reason.message : '学习卡加载失败' }
  finally { if (active && version === generation) pending.value = false }
}
watch(() => props.question.id, async () => {
  const version = ++generation; lesson.value = null; text.value = ''; hint.value = ''; error.value = ''; hintLevel.value = props.question.hintLevel || 0
  phase.value = props.question.introduced ? 'recall' : 'introduce'
  pending.value = phase.value === 'introduce'
  if (pending.value) try { const card = await vocabularyApi.lesson(props.question.id); if (active && version === generation) lesson.value = card }
  catch (reason) { if (active && version === generation) error.value = reason instanceof Error ? reason.message : '学习卡加载失败' }
  finally { if (active && version === generation) pending.value = false }
  await nextTick(); if (active && version === generation && phase.value === 'recall') input.value?.focus()
}, { immediate: true })
watch([() => props.answer, () => props.busy], async ([value, isBusy]) => { if (value && !isBusy) { await nextTick(); if (active) continueButton.value?.focus() } })
async function hide() { phase.value = 'recall'; await nextTick(); input.value?.focus() }
async function getHint() {
  if (pending.value || props.busy || props.answer) return
  pending.value = true; error.value = ''; const id = props.question.id, version = generation
  try { const value = await vocabularyApi.hint(id, Math.min(3, hintLevel.value + 1)); if (active && version === generation) { hint.value = value.text; hintLevel.value = value.level } }
  catch (reason) { if (active && version === generation) error.value = reason instanceof Error ? reason.message : '提示加载失败' }
  finally { if (active && version === generation) pending.value = false }
}
onBeforeUnmount(() => { active = false; generation++ })
</script>

<template>
  <div class="recall-card">
    <header class="recall-top"><span :id="phase === 'introduce' || answer ? 'vocab-question-title' : undefined">{{ answer ? '回忆之后，再巩固' : phase === 'introduce' ? '01 / 场景与搭配' : question.practiceKind === 'COLLOCATION' ? '03 / 固定搭配' : '02 / 遮住答案回忆' }}</span><button v-if="!answer" class="skip" type="button" :disabled="busy || pending" @click="emit('skip')">Skip <span>我已熟悉</span></button></header>
    <p v-if="error" role="alert" class="inline-alert inline-alert--error">{{ error }}<button class="button button--light" :disabled="pending" @click="phase === 'introduce' ? retryLesson() : getHint()">重试</button></p>
    <p v-if="pending && phase === 'introduce'" role="status">正在准备学习卡…</p>
    <template v-if="!answer && phase === 'introduce' && lesson"><VocabularyLessonCard :lesson="lesson" /><footer><p>想一下画面，读一遍完整搭配，然后遮住英文。</p><button class="button button--dark" :disabled="busy || pending" @click="hide">遮住答案，开始回忆 →</button></footer></template>
    <form v-else-if="!answer && phase === 'recall'" class="recall-form" @submit.prevent="!busy && !pending && text.trim() && emit('submit', text.trim())"><p class="prompt-label">{{ question.practiceKind === 'COLLOCATION' ? '用英文写出完整搭配，可用 sb. / sth. 作占位' : '看到这个意思，你能想起哪个单词？' }}</p><h3 id="vocab-question-title">{{ question.prompt }}</h3><label for="vocab-recall-input">{{ question.practiceKind === 'COLLOCATION' ? '完整英文搭配' : '英文单词' }}</label><input id="vocab-recall-input" ref="input" v-model="text" class="field-input" autocomplete="off" autocapitalize="none" spellcheck="false" maxlength="160" :disabled="busy || pending" /><button class="button button--dark" :disabled="busy || pending || !text.trim()">确认回忆</button><button class="hint-button" type="button" :disabled="busy || pending || hintLevel >= 3" @click="getHint">{{ hintLevel >= 3 ? '已显示答案' : ['给我一个画面提示', '提示首字母', '查看答案'][hintLevel] }}</button><p v-if="hint" class="hint" role="status">{{ hint }}</p><p class="subtle">先独立想一下。使用提示会单独记录，不算独立回忆。</p></form>
    <template v-else-if="answer"><section class="result" :class="{ wrong: !answer.correct }"><strong>{{ answer.correct ? '想起来了。' : '再看一遍，把它记回场景里。' }}</strong><p v-if="answer.expectedText">{{ answer.expectedText }}</p><small>{{ evidenceNames[answer.evidence] || answer.evidence }} · {{ answer.message }}</small></section><VocabularyLessonCard v-if="answer.lesson" :lesson="answer.lesson" /><div class="evidence"><span>独立 {{ answer.independentCorrect }} 次</span><span>即时 {{ answer.immediateCorrect }} 次</span><span>提示 {{ answer.promptedCorrect }} 次</span></div><footer><p>穿插其他词再来回忆，明天继续复习。</p><button ref="continueButton" class="button button--dark" :disabled="busy" @click="emit('next')">下一个单词 →</button></footer></template>
  </div>
</template>

<style scoped>
.recall-card{background:var(--paper);padding:28px 32px;border:1px solid var(--line);border-radius:8px}
.recall-card{max-width:720px;margin:auto}.recall-top{display:flex;justify-content:space-between;align-items:center;padding-bottom:18px;border-bottom:1px solid var(--line);font-size:11px;color:var(--muted);letter-spacing:.5px}.skip,.hint-button{font:inherit;border:0;background:none;color:var(--green);cursor:pointer}.skip{padding:10px 0 10px 16px;font-size:15px}.skip span{margin-left:8px;font-size:11px;color:var(--muted)}.recall-form{display:grid;max-width:510px;margin:60px auto 32px;gap:18px}.prompt-label,.subtle{font-size:12px;line-height:1.8;color:var(--muted);margin:0}.recall-form h3{font-size:28px;line-height:1.65;margin:0 0 15px}.recall-form label{font-size:12px;color:var(--muted)}.recall-form input{font:23px ui-monospace,monospace;min-height:58px}.hint-button{padding:10px;text-align:center;font-size:13px}.hint{padding:14px 20px;background:var(--vocab-soft);line-height:1.8;font-size:14px}.result{padding:20px 24px;background:var(--vocab-soft);margin:24px 0}.result.wrong{background:color-mix(in srgb,#bd705e 8%,transparent)}.result p{font:20px ui-monospace,monospace}.result small{font-size:12px;line-height:1.9;color:var(--muted)}.evidence{display:flex;gap:20px;margin:28px 0;color:var(--muted);font-size:12px}footer{border-top:1px solid var(--line);padding-top:20px;display:flex;align-items:center;justify-content:space-between;gap:18px;margin-top:24px}footer p{font-size:12px;line-height:1.8;color:var(--muted)}button:disabled{opacity:.5;cursor:default}
</style>

