<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean; light?: boolean }>(), { compact: false, light: false })
</script>

<template>
  <div title="Personal Knowledge Base and AI Recognition Evaluation" aria-label="个人知识库与 AI 识别评测" class="brand" :class="{ 'brand--light': light }">
    <span class="brand-mark" aria-hidden="true">
      <svg viewBox="0 0 32 32" fill="none"><path d="M8 7h5v10l7-7h6L17 19l7 7h-6l-5-5v5H8Z" fill="currentColor"/><circle cx="25" cy="5" r="2" fill="#719aae"/></svg>
    </span>
    <span v-if="!compact" class="brand-name">knowledge / ai</span>
  </div>
</template>
