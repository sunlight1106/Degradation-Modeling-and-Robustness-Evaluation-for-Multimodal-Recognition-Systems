<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiClientError } from '@/api/client'
import type { FileView, InferenceView } from '@/types/api'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import AppIcon from '@/components/AppIcon.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'

const files = ref<FileView[]>([])
const tasks = ref<InferenceView[]>([])
const tab = ref<'files' | 'reports'>('files')
const loading = ref(true)
const error = ref('')
const canReadFiles = computed(() => authStore.hasAny('file:read', 'file:read:any'))
const canReadReports = computed(() => authStore.hasAny('experiment:read', 'experiment:read:any'))
const canDownloadReports = computed(() => authStore.has('report:download'))

onMounted(async () => {
  try {
    if (!canReadFiles.value && canReadReports.value) tab.value = 'reports'
    const [fileList, taskList] = await Promise.all([
      canReadFiles.value ? api.files() : Promise.resolve([] as FileView[]),
      canReadReports.value ? api.tasks() : Promise.resolve([] as InferenceView[]),
    ])
    files.value = fileList
    tasks.value = taskList
  } catch (reason) {
    error.value = reason instanceof ApiClientError ? reason.message : '下载列表加载失败'
  } finally { loading.value = false }
})

async function download(path: string, filename: string) {
  try { await api.download(path, filename); toastStore.success(`已下载 ${filename}`) }
  catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '下载失败') }
}

const formatSize = (bytes: number) => bytes > 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(2)} MB` : `${(bytes / 1024).toFixed(1)} KB`
const formatDate = (value: string) => new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
</script>

<template>
  <div class="page-stack">
    <section class="page-intro page-intro--split"><div><p class="page-kicker">实验资产</p><h2>下载中心</h2><p>下载有权访问的原始图片、优化图片与结构化实验报告。</p></div><div class="tab-control"><button v-if="canReadFiles" :class="{ active: tab === 'files' }" @click="tab = 'files'">文件 {{ files.length }}</button><button v-if="canReadReports" :class="{ active: tab === 'reports' }" @click="tab = 'reports'">报告 {{ tasks.length }}</button></div></section>
    <div v-if="loading" class="large-skeleton" />
    <div v-else-if="error" class="inline-alert inline-alert--error">{{ error }}</div>
    <section v-else class="panel">
      <template v-if="tab === 'files'">
        <div class="panel-header"><div><h3>文件资产</h3><p>下载操作由后端重新鉴权</p></div></div>
        <EmptyState v-if="!files.length" title="暂无文件" description="上传或生成优化图片后会显示在这里。" />
        <div v-else class="data-table-wrap"><table class="data-table"><thead><tr><th>文件名</th><th>类型</th><th>大小</th><th>所有者</th><th>创建时间</th><th /></tr></thead><tbody>
          <tr v-for="file in files" :key="file.id"><td><div class="table-primary"><span class="file-avatar"><AppIcon name="file" :size="17" /></span><span><strong>{{ file.originalName }}</strong><small>{{ file.sha256.slice(0, 12) }}…</small></span></div></td><td><StatusBadge :status="file.source" /></td><td>{{ formatSize(file.sizeBytes) }}</td><td>{{ file.ownerName }}</td><td>{{ formatDate(file.createdAt) }}</td><td><button class="table-action" @click="download(file.downloadUrl, file.originalName)"><AppIcon name="download" :size="15" /> 下载</button></td></tr>
        </tbody></table></div>
      </template>
      <template v-else>
        <div class="panel-header"><div><h3>实验报告</h3><p>JSON 包含模型、指标、输出和追踪信息</p></div></div>
        <EmptyState v-if="!tasks.length" title="暂无报告" description="完成实验后即可下载结构化报告。" icon="docs" />
        <div v-else class="data-table-wrap"><table class="data-table"><thead><tr><th>实验</th><th>模型</th><th>状态</th><th>trace_id</th><th>创建时间</th><th /></tr></thead><tbody>
          <tr v-for="task in tasks" :key="task.id"><td><div class="table-primary"><span class="file-avatar"><AppIcon name="docs" :size="17" /></span><span><strong>{{ task.taskType === 'LICENSE_PLATE' ? '车牌识别报告' : '票据识别报告' }}</strong><small>{{ task.inputFile.originalName }}</small></span></div></td><td>{{ task.model.name }} <small class="table-muted">v{{ task.model.version }}</small></td><td><StatusBadge :status="task.status" /></td><td><code class="trace-short">{{ task.traceId.slice(0, 8) }}</code></td><td>{{ formatDate(task.createdAt) }}</td><td><button v-if="canDownloadReports" class="table-action" @click="download(task.reportUrl, `experiment-${task.id}.json`)"><AppIcon name="download" :size="15" /> 下载</button></td></tr>
        </tbody></table></div>
      </template>
    </section>
  </div>
</template>
