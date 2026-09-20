<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { authStore } from '@/stores/auth'
const current = ref(0)
const entries = [
  { number: '01', en: 'QUESTION EVERYTHING.', title: '让每个结果，\n经得起质疑。', text: '从真实输入出发，记录基线、退化与优化。建立属于你的识别实验档案。', link: '/app/upload', action: '进入实验台', permission: 'experiment:run', tag: 'EXPERIMENT / 识别实验', color: 'pink' },
  { number: '02', en: 'CONNECT THE EVIDENCE.', title: '把零散线索，\n连成你的答案。', text: '整理知识、保存笔记，让每一次推理都有可回溯的证据。', link: '/app/knowledge', action: '打开知识库', permission: 'knowledge:read', tag: 'ARCHIVE / 知识档案', color: 'cyan' },
  { number: '03', en: 'COMPARE. THEN CONCLUDE.', title: '先对照，\n再下结论。', text: '并列查看模型输出与运行记录。置信度只是线索，真实标注才是判断依据。', link: '/app/comparisons', action: '查看实验对比', permission: 'experiment:read', tag: 'ANALYSIS / 结果对照', color: 'yellow' },
]
const slides = computed(() => entries.filter(s => authStore.has(s.permission) || authStore.has(`${s.permission}:any`)))
const slide = computed(() => slides.value[current.value % slides.value.length])
function move(delta: number) { current.value = (current.value + delta + slides.value.length) % slides.value.length }
</script>
<template>
  <section v-if="slide" class="portal-feature" aria-label="工作空间精选" aria-roledescription="轮播">
    <div class="portal-feature-stage" :data-tone="slide.color">
      <div class="portal-dial" aria-hidden="true"><i /><b /><span>RV</span></div>
      <span class="portal-chapter" aria-hidden="true">{{ slide.number }}</span>
      <div :key="slide.number" class="portal-feature-copy" aria-live="polite">
        <p class="portal-tag">{{ slide.tag }}</p>
        <p class="portal-feature-en">{{ slide.en }}</p>
        <h2>{{ slide.title }}</h2>
        <p class="portal-feature-description">{{ slide.text }}</p>
        <RouterLink :to="slide.link" class="portal-feature-link">{{ slide.action }} <span aria-hidden="true">↗</span></RouterLink>
      </div>
      <span class="portal-edge" aria-hidden="true">NO GUESSWORK. ONLY EVIDENCE.</span>
    </div>
    <div v-if="slides.length > 1" class="portal-feature-controls">
      <span>FEATURED / {{ String(current + 1).padStart(2, '0') }}</span>
      <div class="portal-bullets"><button v-for="(item, i) in slides" :key="item.number" :aria-label="`查看：${item.tag}`" :aria-pressed="current === i" @click="current = i" /></div>
      <div><button aria-label="上一个精选" @click="move(-1)">←</button><button aria-label="下一个精选" @click="move(1)">→</button></div>
    </div>
  </section>
</template>
