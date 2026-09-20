<script setup lang="ts">
const props = defineProps<{ status: string }>()
const labels: Record<string, string> = {
  ACTIVE: '可用', INACTIVE: '停用', COMPLETED: '已完成', FAILED: '失败',
  RUNNING: '运行中', PENDING: '等待中', DISABLED: '已停用', UPLOAD: '原始文件', ENHANCED: '优化文件',
}
</script>

<template>
  <span class="status-badge" :class="`status-badge--${props.status.toLowerCase()}`">
    <span class="status-dot" />{{ labels[props.status] || props.status }}
  </span>
</template>

