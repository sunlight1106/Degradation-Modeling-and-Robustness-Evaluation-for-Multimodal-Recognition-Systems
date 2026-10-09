<script setup lang="ts">
import type { VocabularyLesson } from '@/api/vocabulary'
import WordPronunciation from './WordPronunciation.vue'
defineProps<{ lesson: VocabularyLesson; hideIdentity?: boolean }>()
</script>

<template>
  <div class="lesson-card">
    <header v-if="!hideIdentity"><div><h3>{{ lesson.term }}</h3><p class="phonetic">{{ lesson.ipa }} <span>{{ lesson.pos }}</span></p></div></header>
    <WordPronunciation v-if="!hideIdentity" :text="lesson.term" />
    <p v-if="!hideIdentity" class="meaning">{{ lesson.meaning }}</p>
    <div class="lesson-columns"><div><section class="scene"><span class="label">先记一个画面</span><p>{{ lesson.memoryCue }}</p></section><blockquote v-if="lesson.example"><p>{{ lesson.example }}</p><small>{{ lesson.exampleTranslation }}</small><WordPronunciation v-if="!hideIdentity" :text="lesson.example" /></blockquote></div>
    <section class="combinations"><span class="label">连着记 · 搭配与用法</span><div v-for="item in lesson.collocations" :key="item.pattern" class="pattern"><strong>{{ item.pattern }}</strong><span>{{ item.meaning }}</span><small v-if="item.note">{{ item.note }}</small></div><p v-if="!lesson.collocations.length" class="subtle">这条词库尚未收录固定搭配。先按当前释义使用；私有词书可补充搭配和用法。</p><p class="usage">{{ lesson.usageNote }}</p></section></div>
  </div>
</template>

<style scoped>
.lesson-columns{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.12fr);gap:32px}.lesson-columns .scene{margin-top:0}.lesson-columns .combinations{margin-top:0}.lesson-columns .pattern{grid-template-columns:1fr;gap:7px}.lesson-columns .pattern small{grid-column:auto}@media(max-width:1100px){.lesson-columns{grid-template-columns:1fr;gap:0}}
.lesson-card{max-width:660px;margin:0 auto;color:var(--ink)}header{display:flex;align-items:center;justify-content:space-between;gap:24px}h3{font-size:46px;letter-spacing:-1px;margin:12px 0;overflow-wrap:anywhere}.phonetic{font-family:Georgia,serif;color:var(--muted);font-size:19px;margin:0}.phonetic span{font-family:inherit;margin-left:12px;font-size:14px}.meaning{font-size:21px;margin:22px 0;line-height:1.7}section{margin:23px 0}.scene{padding:20px 24px;background:var(--vocab-soft);border-left:3px solid var(--green)}.label{font-size:11px;color:var(--muted);letter-spacing:1px}.scene p{font-size:16px;line-height:1.85;margin:9px 0 0}.pattern{display:grid;grid-template-columns:1fr 1fr;gap:7px 18px;padding:14px 0;border-bottom:1px solid var(--line);line-height:1.6}.pattern strong{font:500 17px ui-monospace,monospace;color:var(--green)}.pattern span{font-size:14px}.pattern small{grid-column:1/-1;color:var(--muted);font-size:12px}.usage,.subtle{font-size:13px;color:var(--muted);line-height:1.85}blockquote{margin:24px 0;padding:0 0 0 20px;border-left:2px solid var(--line);line-height:1.8}blockquote p{font-size:17px;margin:0 0 6px}blockquote small{font-size:13px;color:var(--muted)}
</style>
