<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { authStore } from '@/stores/auth'
</script>
<template>
  <section class="research-workflow">
    <p class="research-eyebrow">EXPERIMENT NOTES</p><h2>从问题出发，留下可复现的记录。</h2>
    <p>选择输入与模型，检查基线和优化输出，记录观察与结论。</p>
    <ol><li><span>01</span> 准备素材与输入</li><li><span>02</span> 运行识别与对照</li><li><span>03</span> 整理结果与笔记</li></ol>
    <RouterLink v-if="authStore.has('experiment:run')" to="/app/upload">开始一次实验 →</RouterLink>
    <RouterLink v-else to="/docs">阅读实验指南 →</RouterLink>
  </section>
</template>
