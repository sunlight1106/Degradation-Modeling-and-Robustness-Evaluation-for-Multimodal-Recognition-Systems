<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type { InferenceStatus, InferenceView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'
import { createRequestGuard } from '@/lib/requestGuard'

const tasks = ref<InferenceView[]>([])
const loading = ref(true)
const error = ref('')
const query = ref('')
const status = ref<'ALL' | InferenceStatus>('ALL')
const expanded = ref('')
const exporting = ref('')
const recovering = ref('')
const recoveryNotice = ref('')
const guard = createRequestGuard()
const canRecover = (task: InferenceView) => task.status === 'PENDING' && task.requestedById === authStore.state.user?.id && authStore.has('experiment:run')
const canExport = computed(() => authStore.has('report:download'))

const filtered = computed(() => tasks.value.filter(task => {
  const haystack = `${task.traceId} ${task.id} ${task.inputFile.originalName} ${task.model.name}`.toLowerCase()
  return (status.value === 'ALL' || task.status === status.value || status.value === 'RUNNING' && task.status === 'PENDING') && haystack.includes(query.value.trim().toLowerCase())
}))

const counts = computed(() => ({
  all: tasks.value.length,
  completed: tasks.value.filter(task => task.status === 'COMPLETED').length,
  running: tasks.value.filter(task => ['RUNNING', 'PENDING'].includes(task.status)).length,
  failed: tasks.value.filter(task => task.status === 'FAILED').length,
}))

async function refresh() {
  if (recovering.value) return
  const request = guard.start()
  loading.value = true; error.value = ''
  try { const data = await api.tasks(request.signal); if (request.current()) tasks.value = data }
  catch (reason) { if (request.current()) error.value = reason instanceof ApiClientError ? reason.message : '日志加载失败' }
  finally { if (request.current()) loading.value = false }
}
async function recover(task: InferenceView) {
  if (recovering.value || loading.value || !canRecover(task)) return
  if (!window.confirm('将这个等待中的 DEMO 实验重新入队？只恢复本人实验，沿用原任务与 trace_id。仅生成合成结果，不调用个人 AI。若 Worker 正在处理，将等待现有执行结束。')) return
  const request = guard.start()
  recovering.value = task.id; recoveryNotice.value = ''; error.value = ''
  try {
    const updated = await api.recoverTask(task.id, request.signal)
    if (!request.current()) return
    tasks.value = tasks.value.map(item => item.id === updated.id ? updated : item)
    recoveryNotice.value = updated.status === 'PENDING' ? '已重新入队。Worker 处理后请刷新状态；未完成前仍显示等待中。'
      : updated.status === 'COMPLETED' ? 'DEMO 实验已完成。' : '请查看更新后的实验状态和错误说明。'
  } catch (reason) {
    if (request.current()) recoveryNotice.value = reason instanceof ApiClientError ? reason.message : '恢复请求未确认。请先刷新状态，再决定是否重新入队。'
  } finally { if (request.current()) recovering.value = '' }
}
onMounted(refresh)
onBeforeUnmount(() => guard.cancel())
watch(() => authStore.state.user?.id, () => {
  guard.cancel(); tasks.value = []; expanded.value = ''; recovering.value = ''; recoveryNotice.value = ''; loading.value = false; error.value = ''
  if (authStore.state.user) void refresh()
}, { flush: 'sync' })

const formatDate = (value: string) => new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(value))
const adapter = (task: InferenceView) => String((task.optimizedResult || task.baselineResult)?.adapter || 'UNKNOWN')
const usage = (task: InferenceView) => (task.optimizedResult || task.baselineResult)?.usage as Record<string, number> | undefined
async function exportLogs(format: 'pdf' | 'docx' | 'csv' | 'json') {
  exporting.value = format
  try { await api.download(`/api/v1/inference/tasks/export?format=${format}`, `personal-platform-logs.${format}`); toastStore.success(`${format.toUpperCase()} 日志已导出`) }
  catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '日志导出失败') }
  finally { exporting.value = '' }
}
</script>

<template>
  <div class="page-stack logs-page">
    <section class="page-intro page-intro--split"><div><p class="page-kicker">OBSERVABILITY</p><h2>Logs</h2><p>按 trace_id 回看每次模型调用、适配器、状态、时延和 token 用量。</p></div><div v-if="canExport" class="export-actions"><button v-for="format in (['pdf', 'docx', 'csv', 'json'] as const)" :key="format" :disabled="!!exporting" @click="exportLogs(format)"><AppIcon name="download" :size="15" />{{ format.toUpperCase() }}</button></div></section>
    <section class="log-stats"><button :class="{ active: status === 'ALL' }" @click="status = 'ALL'"><span>全部调用</span><strong>{{ counts.all }}</strong></button><button :class="{ active: status === 'COMPLETED' }" @click="status = 'COMPLETED'"><span>已完成</span><strong>{{ counts.completed }}</strong></button><button :class="{ active: status === 'RUNNING' }" @click="status = 'RUNNING'"><span>运行中</span><strong>{{ counts.running }}</strong></button><button :class="{ active: status === 'FAILED' }" @click="status = 'FAILED'"><span>失败</span><strong>{{ counts.failed }}</strong></button></section>
    <section class="panel log-panel">
      <div class="log-toolbar"><label class="asset-search"><AppIcon name="search" :size="18" /><input v-model="query" type="search" placeholder="搜索 trace_id、文件或模型" /></label><span>{{ filtered.length }} entries</span><button type="button" class="button button--ghost" :disabled="loading || !!recovering" @click="refresh">刷新状态</button></div>
      <p v-if="recoveryNotice" role="status" class="inline-alert">{{ recoveryNotice }}</p>
      <div v-if="loading" class="table-skeleton" />
      <div v-else-if="error" class="inline-alert inline-alert--error">{{ error }}</div>
      <EmptyState v-else-if="!filtered.length" title="没有匹配日志" description="模型调用后，完整追踪记录会显示在这里。" icon="logs" />
      <div v-else class="log-list">
        <article v-for="task in filtered" :key="task.id" :class="{ expanded: expanded === task.id }">
          <button class="log-row" @click="expanded = expanded === task.id ? '' : task.id">
            <StatusBadge :status="task.status" /><code>{{ task.traceId.slice(0, 12) }}</code><span><strong>{{ task.taskType === 'LICENSE_PLATE' ? '车牌分析' : '票据分析' }}</strong><small>{{ task.inputFile.originalName }}</small></span><span><small>MODEL</small>{{ task.model.name }}</span><span><small>LATENCY</small>{{ task.optimizedLatencyMs ?? task.baselineLatencyMs ?? '—' }} ms</span><time>{{ formatDate(task.createdAt) }}</time><AppIcon name="chevron" :size="17" />
          </button>
          <div v-if="expanded === task.id" class="log-detail">
            <div><small>Adapter</small><code>{{ adapter(task) }}</code></div><div><small>Prompt tokens</small><strong>{{ usage(task)?.prompt_tokens ?? '—' }}</strong></div><div><small>Completion tokens</small><strong>{{ usage(task)?.completion_tokens ?? '—' }}</strong></div><div><small>Requested by</small><strong>{{ task.requestedBy }}</strong></div>
            <p v-if="task.errorMessage">{{ task.errorMessage }}</p><RouterLink :to="`/app/comparisons?task=${task.id}`">查看完整分析 <AppIcon name="arrow" :size="15" /></RouterLink>
            <div v-if="canRecover(task)" class="log-recovery"><p>长期等待或 Worker 中断后，可将本人 DEMO 实验重新入队。请先刷新确认状态。</p><button type="button" class="button button--ghost" :disabled="!!recovering || loading" @click="recover(task)">{{ recovering === task.id ? '正在恢复…' : '重新入队 DEMO 实验' }}</button></div>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.log-toolbar { flex-wrap: wrap; gap: 12px; }
.log-recovery { grid-column: 1 / -1; align-items: flex-start; }
.log-recovery p { margin: 0; font-size: 12px; line-height: 1.6; }
</style>
