<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Day } from '@/api/admin'
const props = defineProps<{ days: Day[] }>()
type Metric = Exclude<keyof Day, 'date'>
const metrics: { key: Metric; label: string; color: string }[] = [
  { key: 'registrations', label: '新增用户', color: '#447bb8' },
  { key: 'loginUsers', label: '登录用户', color: '#397d68' },
  { key: 'notes', label: '新建笔记', color: '#b07b36' },
  { key: 'aiCalls', label: '个人 AI 调用', color: '#8964ae' },
  { key: 'experiments', label: '识别实验', color: '#aa5c66' },
]
const selected = ref<Metric[]>(['registrations', 'loginUsers'])
const hovered = ref<number | null>(null), keyboard = ref<number | null>(null), table = ref(false)
const activePoint = computed(() => selected.value.length ? hovered.value ?? keyboard.value : null)
const visible = computed(() => metrics.filter(m => selected.value.includes(m.key)))
const maximum = computed(() => Math.max(4, ...props.days.flatMap(day => visible.value.map(m => day[m.key]))))
const scale = computed(() => Math.ceil(maximum.value / 4) * 4)
const x = (i: number) => props.days.length === 1 ? 499 : 54 + i * 890 / Math.max(props.days.length - 1, 1)
const left = (i: number) => Math.max(54, x(i) - 445 / Math.max(props.days.length - 1, 1))
const width = (i: number) => Math.min(944, x(i) + 445 / Math.max(props.days.length - 1, 1)) - left(i)
const y = (value: number) => 250 - value * 210 / scale.value
const line = (key: Metric) => props.days.map((day, i) => `${x(i)},${y(day[key])}`).join(' ')
const day = computed(() => activePoint.value === null ? null : props.days[activePoint.value] ?? null)
function pointer(i: number) { hovered.value = i; keyboard.value = null }
function focus(i: number, event: FocusEvent) { if ((event.target as Element).matches(':focus-visible')) { hovered.value = null; keyboard.value = i } }
function navigate(i: number, event: KeyboardEvent) {
  if (event.key === 'Escape') { hovered.value = null; keyboard.value = null; (event.currentTarget as SVGElement).blur(); return }
  const next = event.key === 'ArrowLeft' ? i - 1 : event.key === 'ArrowRight' ? i + 1 : event.key === 'Home' ? 0 : event.key === 'End' ? props.days.length - 1 : null
  if (next === null) return
  event.preventDefault(); hovered.value = null
  keyboard.value = Math.max(0, Math.min(props.days.length - 1, next))
  ;(event.currentTarget as SVGElement).parentElement?.querySelectorAll<SVGElement>('[data-day]')[keyboard.value]?.focus()
}
watch(() => props.days, () => { hovered.value = null; keyboard.value = null })
function toggle(key: Metric) { selected.value = selected.value.includes(key) ? selected.value.filter(k => k !== key) : [...selected.value, key] }
</script>
<template>
  <div class="admin-chart">
    <div class="admin-chart-legend" aria-label="选择统计指标"><button v-for="metric in metrics" :key="metric.key" :aria-pressed="selected.includes(metric.key)" :style="{ '--series': metric.color }" @click="toggle(metric.key)"><i />{{ metric.label }}</button><button class="chart-table-toggle" :aria-expanded="table" @click="table = !table">{{ table ? '收起数据' : '查看数据表' }}</button></div>
    <div class="chart-readout" aria-live="polite"><template v-if="day"><strong>{{ day.date }}</strong><span v-for="m in visible" :key="m.key">{{ m.label }} <b>{{ day[m.key] }}</b></span></template><span v-else>移到某一天查看数据，或用 Tab 逐日查看。</span></div>
    <svg v-if="days.length" viewBox="0 0 980 290" role="group" aria-label="平台每日统计折线图" @mouseleave="hovered = null" @pointerdown="keyboard = null">
      <g v-for="tick in 5" :key="tick"><line x1="54" :y1="y((tick - 1) * scale / 4)" x2="944" :y2="y((tick - 1) * scale / 4)" class="chart-grid" /><text x="40" :y="y((tick - 1) * scale / 4) + 4" text-anchor="end">{{ (tick - 1) * scale / 4 }}</text></g>
      <rect v-if="activePoint !== null" :x="left(activePoint)" y="30" :width="width(activePoint)" height="220" class="chart-band" pointer-events="none" />
      <polyline v-for="m in visible" :key="m.key" :points="line(m.key)" :stroke="m.color" fill="none" stroke-width="2.3" stroke-linejoin="round" />
      <g v-for="(row, i) in days" :key="row.date" data-day :tabindex="visible.length ? 0 : -1" role="img" :aria-label="`${row.date} ${visible.map(m => `${m.label} ${row[m.key]}`).join('，')}`" @mouseenter="pointer(i)" @mousemove="pointer(i)" @focus="focus(i, $event)" @blur="keyboard = null" @keydown="navigate(i, $event)"><rect :x="left(i)" y="30" :width="width(i)" height="220" fill="transparent" /><circle v-for="m in visible" :key="m.key" :cx="x(i)" :cy="y(row[m.key])" :r="activePoint === i ? 4 : days.length === 1 ? 3 : 0" :fill="m.color" /></g>
      <line v-if="activePoint !== null" :x1="x(activePoint)" :x2="x(activePoint)" y1="30" y2="250" class="chart-cursor" pointer-events="none" />
      <text x="54" y="278">{{ days[0]?.date }}</text><text x="944" y="278" text-anchor="end">{{ days.at(-1)?.date }}</text>
    </svg>
    <p v-else class="table-muted">此时间范围暂无数据。</p>
    <p v-if="!selected.length" class="table-muted">选择上方指标以显示折线。</p>
    <div v-if="table" class="data-table-wrap"><table class="data-table"><thead><tr><th>日期 · UTC</th><th v-for="m in metrics" :key="m.key">{{ m.label }}</th></tr></thead><tbody><tr v-for="row in days" :key="row.date"><td>{{ row.date }}</td><td v-for="m in metrics" :key="m.key">{{ row[m.key] }}</td></tr></tbody></table></div>
  </div>
</template>
<style scoped>
.admin-chart-legend{display:flex;gap:8px;flex-wrap:wrap}.admin-chart-legend button{display:flex;align-items:center;gap:7px;border:1px solid var(--line);background:transparent;padding:8px 13px;color:var(--muted);border-radius:6px;font-size:13px;cursor:pointer;transition:background .15s}.admin-chart-legend button[aria-pressed=true]{background:var(--paper);color:var(--ink);border-color:var(--series)}.admin-chart-legend i{width:7px;height:7px;background:var(--series);border-radius:50%}.admin-chart-legend .chart-table-toggle{margin-left:auto;color:var(--ink-soft)}svg{width:100%;max-height:340px;overflow:visible}svg text{fill:var(--muted);font-size:11px;font-family:inherit}.chart-grid{stroke:var(--line);stroke-dasharray:3 5}.chart-cursor{stroke:var(--muted);stroke-dasharray:4 5}.chart-band{fill:var(--line-soft);fill-opacity:.45}.chart-readout{display:flex;align-items:center;gap:20px;font-size:13px;color:var(--muted);min-height:58px}.chart-readout strong,.chart-readout b{color:var(--ink)}.chart-readout b{margin-left:8px}svg g:focus{outline:none}
</style>
