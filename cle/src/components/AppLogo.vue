<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean; light?: boolean }>(), { compact: false, light: false })
</script>

<template>
  <div class="brand" :class="{ 'brand--light': light }">
    <span class="brand-mark" aria-hidden="true">
      <svg viewBox="0 0 32 32" fill="none">
        <rect x="4.5" y="4.5" width="23" height="23" rx="7" stroke="currentColor" stroke-width="2"/>
        <path d="M10 22V10h6.4a4.2 4.2 0 0 1 0 8.4H10M10 14.2h6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
    </span>
    <span v-if="!compact" class="brand-name">Personal Platform</span>
  </div>
</template>
