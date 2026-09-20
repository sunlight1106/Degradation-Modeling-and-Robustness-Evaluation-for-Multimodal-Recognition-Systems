<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'

import { authStore } from '@/stores/auth'
import { api } from '@/api/client'

type PreviewId = 'quality' | 'route' | 'compare'

// ---------------------------------------------------------------------------
// 演示区数据：优先取自平台内真实完成的推理任务；无数据时回退为合成示例，
// 并在界面上如实标注来源，避免把演示数字冒充真实识别结果。
// ---------------------------------------------------------------------------
interface DemoSource {
  code: string
  baselineRead: string
  optimizedRead: string
  fileName: string
  traceId: string
  adapter: string
  baselinePct: number
  optimizedPct: number
  liftPct: number
  qualityScore: number
  quality: Array<{ label: string; value: number }>
  strategies: string[]
  confidenceSource: string
}


const demo = ref<DemoSource | null>(null)

// 后端 degradation 字段为驼峰命名（图片：lowLight/blur/glare；视频：backgroundNoise/lowVolume）
const DEGRADATION_LABELS: Record<string, string> = {
  lowLight: '低照', glare: '反光', blur: '模糊', motionBlur: '运动模糊', noise: '噪点',
  outOfFocus: '失焦', compression: '压缩', backgroundNoise: '背景噪声', lowVolume: '音量偏低',
}

// 后端 strategies() 返回的策略枚举中文名
const STRATEGY_LABELS: Record<string, string> = {
  LOCAL_CONTRAST_ENHANCEMENT: '局部对比度增强',
  GEOMETRIC_CORRECTION: '几何矫正',
  SUPER_RESOLUTION: '超分辨率',
  AUDIO_HIGH_LOW_PASS: '音频高低通滤波',
  FFT_DENOISE: 'FFT 降噪',
  LOUDNESS_NORMALIZATION: '响度归一化',
  BASELINE: '基线（无优化）',
}

function toPct(value: number | null | undefined): number | null {
  return value == null ? null : Math.round(value * 1000) / 10
}

/** 安全读取嵌套字段：后端把结果解析为 JsonNode 返回。 */
function dig(source: unknown, ...path: string[]): unknown {
  let node = source
  for (const key of path) {
    if (node == null || typeof node !== 'object') return undefined
    node = (node as Record<string, unknown>)[key]
  }
  return node
}

/** 取该路调用的识别文本；后端在 prediction.text 中返回。 */
function readText(result: unknown): string {
  const text = dig(result, 'prediction', 'text')
  return typeof text === 'string' && text.trim() ? text.trim() : ''
}

/** 取该路调用实际启用的处理策略。 */
function readStrategies(result: unknown): string[] {
  const raw = dig(result, 'route', 'strategies')
  if (!Array.isArray(raw)) return []
  return raw.filter((item): item is string => typeof item === 'string')
}

async function loadDemoSource() {
  try {
    const taskList = await api.tasks()

    const completed = taskList
      .filter(item => item.status === 'COMPLETED')
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
    const chosen = completed[0]
    if (!chosen) return

    const baselineResult = chosen.baselineResult as Record<string, unknown> | null
    const optimizedResult = chosen.optimizedResult as Record<string, unknown> | null

    const degradation = (dig(baselineResult, 'quality', 'degradation') ?? {}) as Record<string, number>
    const quality = Object.entries(degradation)
      .slice(0, 3)
      .map(([key, value]) => ({ label: DEGRADATION_LABELS[key] ?? key, value: Number(value) }))

    const qualityScore = Number(dig(baselineResult, 'quality', 'score'))
    const baselineRead = readText(baselineResult)
    const optimizedRead = readText(optimizedResult)
    const adapter = String(dig(baselineResult, 'adapter') ?? 'UNKNOWN')
    const strategies = readStrategies(optimizedResult).length
      ? readStrategies(optimizedResult)
      : readStrategies(baselineResult)

    const baseline = toPct(chosen.baselineConfidence)
    const optimized = toPct(chosen.optimizedConfidence)
    demo.value = {
      code: baselineRead || optimizedRead || 'SAMPLE-01',
      baselineRead: baselineRead || '—',
      optimizedRead: optimizedRead || '—',
      fileName: chosen.inputFile?.originalName ?? '未命名输入',
      traceId: chosen.traceId,
      adapter,
      baselinePct: baseline ?? 0,
      optimizedPct: optimized ?? baseline ?? 0,
      liftPct: baseline != null && optimized != null ? Math.round((optimized - baseline) * 10) / 10 : 0,
      qualityScore: Number.isFinite(qualityScore) ? qualityScore : 0,
      quality,
      strategies,
      confidenceSource: String(dig(baselineResult, 'confidenceSource') ?? ''),
    }
  } catch {
    demo.value = null
  }
}

const isDemoAdapter = computed(() => !demo.value || demo.value.adapter.toUpperCase() === 'DEMO')
const plateCode = computed(() => demo.value?.code ?? '苏C·7R82Q')
const plateBaselineRead = computed(() => demo.value?.baselineRead ?? '苏C · 7R8?Q')
const plateOptimizedRead = computed(() => demo.value?.optimizedRead ?? '苏C · 7R82Q')

/**
 * 两路识别文本是否一致。不一致时不能声称"优化后读对了"——
 * 没有人工真值参照，两个输出都只是模型自述，差异本身不构成准确率提升的证据。
 */
const readsDiffer = computed(() =>
  !!demo.value && demo.value.baselineRead !== demo.value.optimizedRead
    && demo.value.baselineRead !== '—' && demo.value.optimizedRead !== '—')

/** 对比阶段的诚实说明：区分「有真值可判对错」与「仅记录两路输出」。 */
const compareCaveat = computed(() => {
  if (!demo.value) return '合成样例 · 未运行任务'
  if (isDemoAdapter.value) return '演示适配器输出 · 两路文本按文件哈希生成，非真实识别'
  if (readsDiffer.value) return '两路输出不同 · 无人工真值，无法据此判定准确率变化'
  return '两路输出一致 · 置信度为模型自评估值'
})

/**
 * 来源标注必须如实：DEMO 适配器产生的置信度是按文件哈希确定性生成的，
 * 不代表真实模型精度。此时只能声称"任务记录真实"，不能声称"真实识别结果"。
 */
const sourceLabel = computed(() => {
  if (!demo.value) return '合成样例 · 尚无实验记录'
  return isDemoAdapter.value
    ? `演示适配器输出 · 任务 ${demo.value.traceId.slice(0, 8)} 为真实记录`
    : `真实模型输出 · ${demo.value.fileName}`
})
const sourceBadge = computed(() => {
  if (!demo.value) return '演示数据'
  return isDemoAdapter.value ? 'demo 适配器' : '真实模型'
})
const qualityRows = computed(() => demo.value?.quality.length
  ? demo.value.quality
  : [{ label: '低照', value: 0.72 }, { label: '反光', value: 0.48 }, { label: '模糊', value: 0.31 }])
const avgQuality = computed(() => {
  if (demo.value?.qualityScore) return demo.value.qualityScore
  const rows = qualityRows.value
  if (!rows.length) return 0.71
  return rows.reduce((sum, row) => sum + row.value, 0) / rows.length
})

/** 路由阶段：展示任务真实启用的策略，不写死策略名与数量。 */
const strategyRows = computed(() => {
  const actual = demo.value?.strategies ?? []
  if (!actual.length) {
    return [
      { index: '01', label: '局部对比度增强', state: 'selected' },
      { index: '02', label: '几何矫正', state: 'skipped' },
    ]
  }
  return actual.map((name, i) => ({
    index: String(i + 1).padStart(2, '0'),
    label: STRATEGY_LABELS[name] ?? name,
    state: 'selected',
  }))
})
const strategyCount = computed(() => demo.value?.strategies.length ?? 2)
const baselinePct = computed(() => demo.value?.baselinePct ?? 71.2)
const optimizedPct = computed(() => demo.value?.optimizedPct ?? 80.6)
const liftPct = computed(() => demo.value?.liftPct ?? 9.4)
const liftText = computed(() => `${liftPct.value >= 0 ? '+' : ''}${liftPct.value.toFixed(1)}%`)


const stages = computed<Array<{
  id: PreviewId
  index: string
  label: string
  detail: string
  metric: string
  metricLabel: string
}>>(() => [
  { id: 'quality', index: '01', label: '看见输入质量', detail: demo.value ? demo.value.fileName : '低照 · 反光 · 运动模糊', metric: avgQuality.value.toFixed(2), metricLabel: '质量分' },
  { id: 'route', index: '02', label: '选择处理路径', detail: strategyRows.value.map(row => row.label).join(' → ') || '局部对比度 → 几何矫正', metric: String(strategyCount.value), metricLabel: '启用策略' },
  { id: 'compare', index: '03', label: '解释结果变化', detail: '基线与优化保持同一模型', metric: liftText.value, metricLabel: '置信度' },
])

const activeStage = ref<PreviewId>('quality')
const activePreview = computed(() => stages.value.find(stage => stage.id === activeStage.value) || stages.value[0])
const previewStyle = ref<Record<string, string>>({
  '--pointer-x': '68%', '--pointer-y': '32%', '--tilt-x': '0deg', '--tilt-y': '0deg',
})

function movePreview(event: MouseEvent) {
  const bounds = (event.currentTarget as HTMLElement).getBoundingClientRect()
  const x = Math.max(0, Math.min(1, (event.clientX - bounds.left) / bounds.width))
  const y = Math.max(0, Math.min(1, (event.clientY - bounds.top) / bounds.height))
  previewStyle.value = {
    '--pointer-x': `${x * 100}%`, '--pointer-y': `${y * 100}%`,
    '--tilt-x': `${(0.5 - y) * 2.4}deg`, '--tilt-y': `${(x - 0.5) * 3.2}deg`,
  }
}

function resetPreview() {
  previewStyle.value = { '--pointer-x': '68%', '--pointer-y': '32%', '--tilt-x': '0deg', '--tilt-y': '0deg' }
}

onMounted(async () => {
  const user = await authStore.ensureUser()
  if (user) await loadDemoSource()
})

</script>
<template>
<div class="home-experiment">        <div class="pp-hero-preview" :style="previewStyle" aria-label="悬停预览识别流程" @mousemove="movePreview" @mouseleave="resetPreview">
          <div class="pp-preview-aura" />
          <div class="pp-preview-window">
            <div class="pp-window-bar">
              <span class="pp-window-dots"><i /><i /><i /></span><code>personal / evaluation · {{ demo ? demo.traceId.slice(0, 8) : '无记录' }}</code><span class="pp-live" :class="{ 'pp-live--demo': isDemoAdapter }"><i /> {{ sourceBadge }}</span>
            </div>
            <Transition name="pp-preview-swap" mode="out-in">
              <div :key="activeStage" class="pp-preview-stage">
                <div v-if="activeStage === 'quality'" class="pp-quality-visual">
                  <div class="pp-road-scene pp-road-scene--synthetic">
                    <i class="pp-synth-sky" aria-hidden="true" />
                    <i class="pp-synth-road" aria-hidden="true" />
                    <i class="pp-synth-glow" aria-hidden="true" />
                    <div class="pp-synthetic-plate" :aria-label="`示例车牌 ${plateCode}`">
                      <span class="pp-synthetic-plate-province">{{ plateCode.slice(0, 1) }}</span><span class="pp-synthetic-plate-code">{{ plateCode.slice(1) }}</span>
                    </div>
                    <span class="pp-demo-flag">{{ sourceLabel }}</span>
                    <i class="pp-scan" />
                  </div>
                  <div class="pp-diagnostic-list">
                    <span v-for="row in qualityRows" :key="row.label"><i :style="{ '--value': `${Math.round(row.value * 100)}%` }" />{{ row.label }} <b>{{ row.value.toFixed(2) }}</b></span>
                  </div>
                </div>
                <div v-else-if="activeStage === 'route'" class="pp-route-visual">
                  <div class="pp-route-node"><span class="pp-route-plate-mini">{{ plateCode }}</span><small>INPUT</small><strong>{{ demo ? demo.fileName : '合成车牌样例' }}</strong><span>quality {{ avgQuality.toFixed(2) }}</span></div>
                  <div class="pp-route-line"><i /><i /><i /></div>
                  <div class="pp-route-stack">
                    <span v-for="row in strategyRows" :key="row.index" :class="{ 'is-selected': row.state === 'selected' }"><b>{{ row.index }}</b> {{ row.label }} <small>{{ row.state }}</small></span>
                  </div>
                </div>
                <div v-else class="pp-compare-visual">
                  <div class="pp-compare-pane pp-compare-pane--before">
                    <i class="pp-scene-plate" aria-hidden="true">{{ plateCode }}</i>
                    <small>BASELINE</small>
                    <span class="pp-read">输出 {{ plateBaselineRead }}</span>
                    <b>{{ baselinePct.toFixed(1) }}%</b>
                  </div>
                  <div class="pp-compare-divider"><span><AppIcon name="compare" :size="14" /></span></div>
                  <div class="pp-compare-pane pp-compare-pane--after">
                    <i class="pp-scene-plate" aria-hidden="true">{{ plateCode }}</i>
                    <small>OPTIMIZED</small>
                    <span class="pp-read pp-read--ok">输出 {{ plateOptimizedRead }}</span>
                    <b>{{ optimizedPct.toFixed(1) }}%</b>
                  </div>
                  <p class="pp-compare-caveat">{{ compareCaveat }}</p>
                </div>
                <div class="pp-stage-summary">
                  <div><small>{{ activePreview.metricLabel }}</small><strong>{{ activePreview.metric }}</strong></div>
                  <div><small>当前阶段</small><strong>{{ activePreview.label }}</strong></div>
                  <span class="pp-result-chip">{{ activeStage === 'compare' ? 'verified' : 'running' }}</span>
                </div>
              </div>
            </Transition>
            <div class="pp-stage-tabs">
              <button v-for="stage in stages" :key="stage.id" type="button" :class="{ 'is-active': activeStage === stage.id }" @mouseenter="activeStage = stage.id" @focus="activeStage = stage.id" @click="activeStage = stage.id">
                <span>{{ stage.index }}</span><span><strong>{{ stage.label }}</strong><small>{{ stage.detail }}</small></span><AppIcon name="arrow" :size="15" />
              </button>
            </div>
          </div>
          <div class="pp-floating-metric"><small>confidence lift · {{ sourceBadge }}</small><strong>{{ liftText }}</strong><span>same model · same input</span></div>
        </div>
</div>
</template>
