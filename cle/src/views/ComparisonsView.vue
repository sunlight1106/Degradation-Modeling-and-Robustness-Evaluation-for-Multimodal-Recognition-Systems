<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type { InferenceView } from '@/types/api'
import ComparisonSlider from '@/components/ComparisonSlider.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import AppIcon from '@/components/AppIcon.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'

const route = useRoute()
const tasks = ref<InferenceView[]>([])
const selectedId = ref('')
const originalUrl = ref('')
const optimizedUrl = ref('')
const loading = ref(true)
const error = ref('')

const selected = computed(() => tasks.value.find(task => task.id === selectedId.value) || null)
const isVideo = computed(() => selected.value?.taskType === 'VIDEO_ANALYSIS')
const confidenceLift = computed(() => selected.value?.baselineConfidence != null && selected.value.optimizedConfidence != null
  ? selected.value.optimizedConfidence - selected.value.baselineConfidence : null)
const latencyCost = computed(() => selected.value?.baselineLatencyMs != null && selected.value.optimizedLatencyMs != null
  ? selected.value.optimizedLatencyMs - selected.value.baselineLatencyMs : null)

interface AnalysisResult {
  adapter?: string
  provider?: string
  confidenceSource?: string
  quality?: { score?: number; bucket?: string; labels?: string[]; degradation?: Record<string, number> }
  analysis?: { summary?: string; evidence?: string[]; uncertainties?: string[] }
  warnings?: string[]
  usage?: Record<string, number>
}

const modelAnalysis = computed(() => (selected.value?.optimizedResult || selected.value?.baselineResult || {}) as AnalysisResult)

onMounted(async () => {
  try {
    tasks.value = await api.tasks()
    const requested = typeof route.query.task === 'string' ? route.query.task : ''
    selectedId.value = tasks.value.some(task => task.id === requested) ? requested : tasks.value[0]?.id || ''
  } catch (reason) {
    error.value = reason instanceof ApiClientError ? reason.message : '实验记录加载失败'
  } finally {
    loading.value = false
  }
})

watch(selected, async task => {
  releaseUrls()
  if (!task) return
  try {
    const urls = await Promise.all([
      api.blobUrl(task.inputFile.contentUrl),
      task.outputFile ? api.blobUrl(task.outputFile.contentUrl) : Promise.resolve(''),
    ])
    originalUrl.value = urls[0]
    optimizedUrl.value = urls[1]
  } catch {
    error.value = '对比媒体加载失败'
  }
})

onBeforeUnmount(releaseUrls)

function releaseUrls() {
  if (originalUrl.value) URL.revokeObjectURL(originalUrl.value)
  if (optimizedUrl.value) URL.revokeObjectURL(optimizedUrl.value)
  originalUrl.value = ''
  optimizedUrl.value = ''
}

function label(task: InferenceView) {
  return `${task.taskType === 'LICENSE_PLATE' ? '车牌' : task.taskType === 'RECEIPT' ? '票据' : '视频'} · ${task.inputFile.originalName}`
}

function prediction(task: InferenceView | null, optimized: boolean) {
  const result = (optimized ? task?.optimizedResult : task?.baselineResult) as { prediction?: { text?: string; fields?: Record<string, unknown> } } | null
  return result?.prediction || null
}

function formatField(value: unknown) {
  return typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value ?? '—')
}

const degradationLabel: Record<string, string> = {
  lowLight: '低照', blur: '模糊', glare: '反光', perspective: '透视', compression: '压缩', backgroundNoise: '背景杂音', lowVolume: '音量偏低',
}

async function downloadReport() {
  if (!selected.value) return
  try {
    await api.download(selected.value.reportUrl, `experiment-${selected.value.id}.json`)
    toastStore.success('实验报告已下载')
  } catch (reason) {
    toastStore.error(reason instanceof ApiClientError ? reason.message : '报告下载失败')
  }
}

const formatDate = (value: string) => new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
}).format(new Date(value))
</script>

<template>
  <div class="page-stack">
    <section class="page-intro page-intro--split">
      <div><p class="page-kicker">结果分析</p><h2>优化前后对比</h2><p>同一输入、同一模型版本下查看识别结果、置信度收益与时延代价。</p></div>
      <div v-if="tasks.length" class="comparison-picker">
        <label>选择实验</label>
        <select v-model="selectedId" class="field-input"><option v-for="task in tasks" :key="task.id" :value="task.id">{{ label(task) }}</option></select>
      </div>
    </section>

    <div v-if="loading" class="large-skeleton" />
    <div v-else-if="error && !selected" class="inline-alert inline-alert--error">{{ error }}</div>
    <EmptyState v-else-if="!selected" title="还没有可对比的实验" description="先上传图片，并启用质量感知优化运行一次实验。" icon="compare">
      <RouterLink to="/app/upload" class="button button--dark button--small">新建实验</RouterLink>
    </EmptyState>

    <template v-else>
      <section class="comparison-meta panel">
        <div><span class="file-avatar"><AppIcon :name="selected.taskType === 'VIDEO_ANALYSIS' ? 'video' : selected.taskType === 'LICENSE_PLATE' ? 'model' : 'docs'" :size="18" /></span><div><strong>{{ label(selected) }}</strong><small>{{ selected.model.name }} · v{{ selected.model.version }}</small></div></div>
        <StatusBadge :status="selected.status" />
        <div class="trace-display"><span>TRACE ID</span><code>{{ selected.traceId }}</code></div>
        <button v-if="authStore.has('report:download')" class="button button--ghost button--small" @click="downloadReport"><AppIcon name="download" :size="16" /> 下载报告</button>
      </section>

      <section class="comparison-workspace">
        <div class="panel image-comparison-panel">
          <div class="panel-header"><div><h3>{{ isVideo ? '视频与音轨对照' : '可视化差异' }}</h3><p>{{ isVideo ? '左侧原始视频，右侧为音轨降噪与响度归一化版本' : '拖动中间分隔线查看处理前后' }}</p></div><span class="comparison-help">{{ isVideo ? '同模型 · 双路' : '拖动 ↔' }}</span></div>
          <div v-if="isVideo" class="video-comparison"><figure><video :src="originalUrl" controls playsinline /><figcaption>BASELINE · 原始音轨</figcaption></figure><figure><video :src="optimizedUrl || originalUrl" controls playsinline /><figcaption>OPTIMIZED · 降噪与响度归一化</figcaption></figure></div>
          <ComparisonSlider v-else :original-url="originalUrl" :optimized-url="optimizedUrl" />
        </div>

        <aside class="comparison-metrics">
          <article class="panel result-card">
            <div class="result-card-heading"><span class="result-label">基线结果</span><small>无优化策略</small></div>
            <strong class="prediction-text">{{ prediction(selected, false)?.text || '—' }}</strong>
            <div class="confidence-bar"><i :style="{ width: `${(selected.baselineConfidence || 0) * 100}%` }" /></div>
            <div class="result-stats"><span>置信度 <b>{{ selected.baselineConfidence != null ? `${(selected.baselineConfidence * 100).toFixed(1)}%` : '—' }}</b></span><span>时延 <b>{{ selected.baselineLatencyMs ?? '—' }} ms</b></span></div>
          </article>
          <article class="panel result-card result-card--optimized">
            <div class="result-card-heading"><span class="result-label">优化结果</span><small>质量感知策略</small></div>
            <strong class="prediction-text">{{ prediction(selected, true)?.text || '未启用' }}</strong>
            <div class="confidence-bar"><i :style="{ width: `${(selected.optimizedConfidence || 0) * 100}%` }" /></div>
            <div class="result-stats"><span>置信度 <b>{{ selected.optimizedConfidence != null ? `${(selected.optimizedConfidence * 100).toFixed(1)}%` : '—' }}</b></span><span>时延 <b>{{ selected.optimizedLatencyMs ?? '—' }} ms</b></span></div>
          </article>
          <article class="panel delta-card">
            <div><span>置信度变化</span><strong :class="{ positive: (confidenceLift || 0) > 0 }">{{ confidenceLift != null ? `${confidenceLift >= 0 ? '+' : ''}${(confidenceLift * 100).toFixed(1)}%` : '—' }}</strong></div>
            <div><span>额外时延</span><strong>{{ latencyCost != null ? `+${latencyCost} ms` : '—' }}</strong></div>
          </article>
        </aside>
      </section>

      <section class="panel result-detail-grid">
        <div><p class="page-kicker">输出字段</p><h3>结构化识别结果</h3><dl v-if="prediction(selected, true)?.fields"><template v-for="(value, key) in prediction(selected, true)?.fields" :key="key"><dt>{{ key }}</dt><dd>{{ formatField(value) }}</dd></template></dl><p v-else class="table-muted">本次实验没有优化字段输出。</p></div>
        <div><p class="page-kicker">实验上下文</p><h3>可复现信息</h3><dl><dt>创建时间</dt><dd>{{ formatDate(selected.createdAt) }}</dd><dt>模型编码</dt><dd>{{ selected.model.code }}</dd><dt>模型版本</dt><dd>{{ selected.model.version }}</dd><dt>执行人</dt><dd>{{ selected.requestedBy }}</dd></dl></div>
        <div class="demo-warning" :class="{ 'is-live': modelAnalysis.adapter?.endsWith('_API') }"><AppIcon name="shield" :size="22" /><div><strong>{{ modelAnalysis.adapter?.endsWith('_API') ? '真实 API · 未校准置信度' : '演示适配器' }}</strong><p>{{ modelAnalysis.adapter?.endsWith('_API') ? `识别与质量分析来自 ${modelAnalysis.provider || selected.model.provider}；置信度仍需标注集校准。` : '这些指标只用于界面与链路验证，不能写入论文结论。' }}</p></div></div>
      </section>

      <section class="panel model-analysis-panel">
        <header class="panel-header"><div><p class="page-kicker">VISION ANALYSIS</p><h3>模型对输入的解释</h3><p>{{ modelAnalysis.provider || modelAnalysis.adapter || 'Unknown adapter' }} · {{ modelAnalysis.confidenceSource || 'confidence source unknown' }}</p></div><span class="analysis-quality-score"><small>QUALITY</small><strong>{{ modelAnalysis.quality?.score != null ? modelAnalysis.quality.score.toFixed(2) : '—' }}</strong><i>{{ modelAnalysis.quality?.bucket || 'UNKNOWN' }}</i></span></header>
        <div class="analysis-body">
          <div class="analysis-summary"><span class="analysis-icon"><AppIcon name="spark" :size="21" /></span><div><h4>分析摘要</h4><p>{{ modelAnalysis.analysis?.summary || '本次结果没有返回分析摘要。' }}</p><div class="quality-labels"><span v-for="label in modelAnalysis.quality?.labels || []" :key="label">{{ label }}</span></div></div></div>
          <div class="degradation-chart"><h4>退化强度</h4><div v-for="(value, key) in modelAnalysis.quality?.degradation || {}" :key="key"><span>{{ degradationLabel[String(key)] || key }}</span><i><b :style="{ width: `${Math.max(0, Math.min(1, Number(value))) * 100}%` }" /></i><strong>{{ Number(value).toFixed(2) }}</strong></div><p v-if="!modelAnalysis.quality?.degradation">暂无退化维度。</p></div>
          <div class="analysis-evidence"><div><h4>判断依据</h4><ul><li v-for="item in modelAnalysis.analysis?.evidence || ['暂无证据说明']" :key="item">{{ item }}</li></ul></div><div><h4>不确定性</h4><ul><li v-for="item in modelAnalysis.analysis?.uncertainties || ['未返回不确定性']" :key="item">{{ item }}</li></ul></div></div>
          <div class="analysis-usage"><span><small>PROMPT TOKENS</small><strong>{{ modelAnalysis.usage?.prompt_tokens ?? '—' }}</strong></span><span><small>COMPLETION TOKENS</small><strong>{{ modelAnalysis.usage?.completion_tokens ?? '—' }}</strong></span><span><small>TOTAL TOKENS</small><strong>{{ modelAnalysis.usage?.total_tokens ?? '—' }}</strong></span><span v-if="modelAnalysis.warnings?.length"><small>WARNINGS</small><strong>{{ modelAnalysis.warnings.length }}</strong></span></div>
        </div>
      </section>
    </template>
  </div>
</template>
