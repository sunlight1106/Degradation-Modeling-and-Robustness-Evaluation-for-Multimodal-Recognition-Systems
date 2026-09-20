<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type { SharedNoteView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import { countWords, readingMinutes, renderMarkdown } from '@/lib/markdown'

const route = useRoute()

const note = ref<SharedNoteView | null>(null)
const loading = ref(true)
const error = ref('')

const html = computed(() => renderMarkdown(note.value?.body))
const words = computed(() => countWords(note.value?.body))
const minutes = computed(() => readingMinutes(note.value?.body))

const refTypeLabel: Record<string, string> = { FILE: '文件', TASK: '任务', ENTRY: '知识卡' }

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  const raw = route.params.token
  const token = typeof raw === 'string' ? raw : ''
  if (!token) {
    error.value = '分享链接无效'
    loading.value = false
    return
  }
  try {
    note.value = await api.sharedNote(token)
  } catch (reason) {
    error.value = reason instanceof ApiClientError ? reason.message : '分享内容加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="shared-page">
    <header class="shared-nav">
      <RouterLink to="/" class="shared-brand">Personal Platform</RouterLink>
      <RouterLink :to="{ name: 'notes' }" class="shared-back">
        <AppIcon name="note" :size="16" /> 我的笔记
      </RouterLink>
    </header>

    <main class="shared-main">
      <p v-if="loading" class="shared-loading"><span class="mini-loader" /> 正在加载分享内容…</p>

      <section v-else-if="error" class="panel shared-error">
        <span class="shared-error-icon"><AppIcon name="lock" :size="22" /></span>
        <h2>无法查看</h2>
        <p>{{ error }}</p>
        <RouterLink to="/" class="button button--dark">返回首页</RouterLink>
      </section>

      <article v-else-if="note" class="panel shared-note">
        <header class="shared-note-head">
          <p class="page-kicker">SHARED NOTE · 只读</p>
          <h1>{{ note.title }}</h1>
          <div class="shared-note-meta">
            <span class="shared-owner"><i>{{ note.ownerName.slice(0, 1) }}</i>{{ note.ownerName }}</span>
            <span>更新于 {{ formatDate(note.updatedAt) }}</span>
            <span><b>{{ words }}</b> 字 · 约 <b>{{ minutes }}</b> 分钟</span>
          </div>
          <div v-if="note.tags.length" class="shared-tags">
            <i v-for="tag in note.tags" :key="tag">{{ tag }}</i>
          </div>
        </header>

        <div class="markdown-body shared-note-body" v-html="html" />

        <footer v-if="note.references.length" class="shared-refs">
          <h3>引用</h3>
          <div class="note-refs">
            <article v-for="reference in note.references" :key="reference.id" class="note-ref" :class="{ 'note-ref--blocked': !reference.accessible }">
              <span class="note-ref-kind">{{ refTypeLabel[reference.referenceType] || reference.referenceType }}</span>
              <div>
                <strong>{{ reference.displayTitle }}</strong>
                <small>{{ reference.displayMeta || (reference.accessible ? '' : '不可访问') }}</small>
              </div>
            </article>
          </div>
        </footer>
      </article>
    </main>
  </div>
</template>
