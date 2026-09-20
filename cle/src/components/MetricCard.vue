<script setup lang="ts">
withDefaults(defineProps<{
  label: string
  value: string | number
  hint?: string
  tone?: 'default' | 'green' | 'amber'
}>(), { hint: '', tone: 'default' })
</script>

<template>
  <article class="metric-card" :class="`metric-card--${tone}`">
    <span class="metric-label">{{ label }}</span>
    <strong class="metric-value">{{ value }}</strong>
    <span v-if="hint" class="metric-hint">{{ hint }}</span>
  </article>
</template>

