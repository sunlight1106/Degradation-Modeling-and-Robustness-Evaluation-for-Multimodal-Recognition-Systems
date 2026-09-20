<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type { FileView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { authStore } from '@/stores/auth'
import { toastStore } from '@/stores/toast'

const files = ref<FileView[]>([])
const previews = reactive<Record<string, string>>({})
const loading = ref(true)
const error = ref('')
const query = ref('')
const source = ref<'ALL' | 'UPLOAD' | 'ENHANCED'>('ALL')
const selected = ref<FileView | null>(null)

const filtered = computed(() => files.value.filter(file => {
  const sourceMatches = source.value === 'ALL' || file.source === source.value
  const textMatches = file.originalName.toLowerCase().includes(query.value.trim().toLowerCase())
  return sourceMatches && textMatches
}))

onMounted(async () => {
  try {
    files.value = await api.files()
    await Promise.all(files.value.map(async file => {
      try { previews[file.id] = await api.blobUrl(file.contentUrl) } catch { previews[file.id] = '' }
    }))
  } catch (reason) {
    error.value = reason instanceof ApiClientError ? reason.message : '媒体列表加载失败'
  } finally { loading.value = false }
})

onBeforeUnmount(() => Object.values(previews).forEach(url => { if (url) URL.revokeObjectURL(url) }))

async function download(file: FileView) {
  try {
    await api.download(file.downloadUrl, file.originalName)
    toastStore.success(`已下载 ${file.originalName}`)
  } catch (reason) {
    toastStore.error(reason instanceof ApiClientError ? reason.message : '下载失败')
  }
}

function playPreview(event: Event) {
  const video = (event.currentTarget as HTMLElement).querySelector('video')
  video?.play().catch(() => undefined)
}

function pausePreview(event: Event) {
  const video = (event.currentTarget as HTMLElement).querySelector('video')
  if (!video) return
  video.pause()
  video.currentTime = 0
}

const formatSize = (bytes: number) => bytes > 1024 * 1024
  ? `${(bytes / 1024 / 1024).toFixed(2)} MB` : `${(bytes / 1024).toFixed(1)} KB`
const formatDate = (value: string) => new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
</script>

<template>
  <div class="page-stack images-page">
    <section class="page-intro page-intro--split">
      <div><p class="page-kicker">MEDIA LIBRARY</p><h2>Images & Video</h2><p>真实图片、视频、增强输出、病毒扫描与对象存储状态集中在同一个媒体库。</p></div>
      <RouterLink v-if="authStore.has('file:write')" to="/app/upload" class="button button--dark"><AppIcon name="upload" :size="17" /> 上传媒体</RouterLink>
    </section>

    <div class="asset-toolbar panel">
      <label class="asset-search"><AppIcon name="search" :size="18" /><input v-model="query" type="search" placeholder="搜索文件名" /></label>
      <div class="asset-filters"><button :class="{ active: source === 'ALL' }" @click="source = 'ALL'">全部 {{ files.length }}</button><button :class="{ active: source === 'UPLOAD' }" @click="source = 'UPLOAD'">原始输入</button><button :class="{ active: source === 'ENHANCED' }" @click="source = 'ENHANCED'">增强输出</button></div>
    </div>

    <div v-if="loading" class="image-loading-grid"><span v-for="i in 8" :key="i" /></div>
    <div v-else-if="error" class="inline-alert inline-alert--error">{{ error }}</div>
    <EmptyState v-else-if="!filtered.length" title="没有匹配的媒体" description="调整筛选条件，或上传一个新的真实样本。" icon="images" />
    <section v-else class="asset-grid">
      <article v-for="file in filtered" :key="file.id" class="asset-card">
        <button class="asset-card-image" type="button" @mouseenter="playPreview" @mouseleave="pausePreview" @focus="playPreview" @blur="pausePreview" @click="selected = file">
          <video v-if="previews[file.id] && file.contentType.startsWith('video/')" :src="previews[file.id]" muted playsinline />
          <img v-else-if="previews[file.id]" :src="previews[file.id]" :alt="file.originalName" />
          <span v-else><AppIcon name="images" :size="28" /></span>
          <i class="asset-hover-action"><AppIcon name="eye" :size="17" /> 预览</i>
          <StatusBadge :status="file.source" />
        </button>
        <div class="asset-card-copy"><div><strong>{{ file.originalName }}</strong><small>{{ formatSize(file.sizeBytes) }} · {{ formatDate(file.createdAt) }}</small></div><button class="icon-button" title="下载" @click="download(file)"><AppIcon name="download" :size="18" /></button></div>
      </article>
    </section>

    <Transition name="modal-fade">
      <div v-if="selected" class="image-modal" @click.self="selected = null">
        <div class="image-modal-panel">
          <header><div><strong>{{ selected.originalName }}</strong><small>{{ selected.ownerName }} · {{ selected.sha256.slice(0, 16) }}…</small></div><button class="icon-button" @click="selected = null"><AppIcon name="close" :size="19" /></button></header>
          <video v-if="selected.contentType.startsWith('video/')" :src="previews[selected.id]" controls playsinline />
          <img v-else :src="previews[selected.id]" :alt="selected.originalName" />
          <footer><StatusBadge :status="selected.source" /><span>{{ selected.scanStatus }} · {{ selected.storageBackend }} · {{ formatSize(selected.sizeBytes) }}</span><button class="button button--light button--small" @click="download(selected)"><AppIcon name="download" :size="16" /> 下载原文件</button></footer>
        </div>
      </div>
    </Transition>
  </div>
</template>
