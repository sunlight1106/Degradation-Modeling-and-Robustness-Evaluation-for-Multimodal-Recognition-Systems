<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean; light?: boolean }>(), { compact: false, light: false })
</script>

<template>
  <div class="brand" :class="{ 'brand--light': light }">
    <span class="brand-mark" aria-hidden="true">
      <svg viewBox="0 0 32 32" fill="none"><path d="M8 25V10h5v3c2-3 6-4 10-3v5c-6-2-10 1-10 6v4Z" fill="currentColor"/><circle cx="23" cy="24" r="2" fill="#719aae"/></svg>
    </span>
    <span v-if="!compact" class="brand-name">personal / research</span>
  </div>
</template>
