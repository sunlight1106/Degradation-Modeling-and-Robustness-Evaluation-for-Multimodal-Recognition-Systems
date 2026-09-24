<script setup lang="ts">
import { RouterView, useRoute } from 'vue-router'
import ToastStack from '@/components/ToastStack.vue'
import FloatingEntryBadge from '@/components/FloatingEntryBadge.vue'
const route = useRoute()
</script>

<template>
  <RouterView />
  <ToastStack />
  <FloatingEntryBadge v-if="!route.matched.some(record => record.meta.requiresAuth)" />
</template>

