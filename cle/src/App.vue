<script setup lang="ts">
import { RouterView } from 'vue-router'
import ToastStack from '@/components/ToastStack.vue'
import FloatingEntryBadge from '@/components/FloatingEntryBadge.vue'
</script>

<template>
  <RouterView />
  <ToastStack />
  <FloatingEntryBadge />
</template>

