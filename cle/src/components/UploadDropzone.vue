<script setup lang="ts">
import { ref } from 'vue'
import AppIcon from './AppIcon.vue'


const emit = defineEmits<{ selected: [file: File] }>()
const dragging = ref(false)
const input = ref<HTMLInputElement | null>(null)

function select(files: FileList | null) {
  const file = files?.[0]
  if (file) emit('selected', file)
}

function drop(event: DragEvent) {
  dragging.value = false
  select(event.dataTransfer?.files || null)
}
</script>

<template>
  <button
    type="button"
    class="dropzone"
    :class="{ 'dropzone--dragging': dragging }"
    @click="input?.click()"
    @dragover.prevent="dragging = true"
    @dragleave.prevent="dragging = false"
    @drop.prevent="drop"
  >
    <span class="dropzone-icon"><AppIcon name="upload" :size="25" /></span>
    <strong>拖入图片或视频，或点击选择</strong>
    <span class="dropzone-hint">支持 JPEG、PNG、WEBP、MP4、WEBM，单文件不超过 20 MB</span>
    <span class="dropzone-formats" aria-hidden="true">JPEG / PNG / WEBP / MP4 / WEBM</span>
    <input ref="input" type="file" accept="image/jpeg,image/png,image/webp,video/mp4,video/webm" hidden @change="select(($event.target as HTMLInputElement).files)" />
  </button>
</template>
