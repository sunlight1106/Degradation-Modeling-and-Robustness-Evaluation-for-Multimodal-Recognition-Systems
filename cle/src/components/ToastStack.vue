<script setup lang="ts">
import AppIcon from './AppIcon.vue'
import { toastStore } from '@/stores/toast'
</script>

<template>
  <div class="toast-stack" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="item in toastStore.state.items" :key="item.id" class="toast" :class="`toast--${item.kind}`">
        <span class="toast-dot" />
        <span>{{ item.message }}</span>
        <button class="icon-button" aria-label="关闭通知" @click="toastStore.remove(item.id)"><AppIcon name="close" :size="16" /></button>
      </div>
    </TransitionGroup>
  </div>
</template>

