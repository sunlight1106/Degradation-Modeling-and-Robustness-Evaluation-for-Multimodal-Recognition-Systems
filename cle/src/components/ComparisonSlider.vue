<script setup lang="ts">
import { ref } from 'vue'

withDefaults(defineProps<{
  originalUrl?: string
  optimizedUrl?: string
  originalLabel?: string
  optimizedLabel?: string
}>(), { originalUrl: '', optimizedUrl: '', originalLabel: '原始输入', optimizedLabel: '策略优化后' })

const position = ref(52)
</script>

<template>
  <div class="comparison-slider" :style="{ '--position': `${position}%` }">
    <div v-if="!originalUrl" class="comparison-placeholder">
      <span>选择一条已完成的优化实验</span>
    </div>
    <template v-else>
      <img class="comparison-image comparison-image--original" :src="originalUrl" :alt="originalLabel" />
      <div class="comparison-clip">
        <img class="comparison-image comparison-image--optimized" :src="optimizedUrl || originalUrl" :alt="optimizedLabel" />
      </div>
      <span class="comparison-label comparison-label--left">{{ optimizedLabel }}</span>
      <span class="comparison-label comparison-label--right">{{ originalLabel }}</span>
      <div class="comparison-divider"><span>↔</span></div>
      <input v-model="position" class="comparison-range" type="range" min="0" max="100" aria-label="拖动查看优化前后对比" />
    </template>
  </div>
</template>

