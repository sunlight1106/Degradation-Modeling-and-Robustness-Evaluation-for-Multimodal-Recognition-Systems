<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { personalApi } from '@/api/personal'
import type { TrainingEnvironment, TrainingJob, TrainingSample } from '@/types/personal'
import LoraColabCard from './LoraColabCard.vue'

const environment = ref<TrainingEnvironment | null>(null), jobs = ref<TrainingJob[]>([]), selected = ref('')
const architecture=ref('mlp'), seed=ref(42), validationFraction=ref(.2)
const name = ref('我的文本分类实验'), epochs = ref(15), learningRate = ref(.01), samples = ref<TrainingSample[]>([]), filename = ref('')
const error = ref(''), busy = ref(false), loading = ref(true), input = ref(''), prediction = ref<{ label: string; score: number }[]>([])
const job = computed(() => jobs.value.find(item => item.id === selected.value))
const labels = computed(() => [...new Set(samples.value.map(item => item.label))])
const running = (item: TrainingJob) => ['QUEUED', 'RUNNING', 'CANCELLING'].includes(item.status)
const latest = computed(() => job.value?.metrics.at(-1))
const states: Record<string, string> = { QUEUED: '排队中', RUNNING: '训练中', CANCELLING: '停止中', CANCELLED: '已取消', FAILED: '未完成', COMPLETED: '已完成' }
let active = true, timer: ReturnType<typeof setTimeout> | undefined, fileGeneration = 0, loadGeneration = 0
async function load(initial = false) {
  const generation = ++loadGeneration
  if (initial) loading.value = true
  try {
    const [env, list] = await Promise.all([personalApi.trainingEnvironment(), personalApi.trainingJobs()])
    if (!active || generation !== loadGeneration) return
    environment.value = env; jobs.value = list
    if (!list.some(item => item.id === selected.value)) selected.value = list[0]?.id || ''
    if (initial) error.value = ''
  } catch (e) { if (active && generation === loadGeneration) error.value = e instanceof Error ? e.message : '训练环境未就绪' }
  finally {
    if (active && generation === loadGeneration) { loading.value = false; clearTimeout(timer); schedule() }
  }
}
function schedule() { timer = setTimeout(() => { if (!document.hidden) void load(); else schedule() }, jobs.value.some(running) ? 3000 : 15000) }
function choose(id: string) { selected.value = id; prediction.value = []; input.value = '' }
async function upload(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0], generation = ++fileGeneration
  samples.value = []; filename.value = ''; error.value = ''
  if (!file) return
  if (file.size > 140000) { error.value = '请选择不超过 140 KB 的 JSON 文件'; return }
  try {
    const data: unknown = JSON.parse(await file.text())
    if (!active || generation !== fileGeneration) return
    if (!Array.isArray(data) || data.length < 8 || data.length > 500 || data.some(row => !row || typeof row.text !== 'string' || !row.text.trim() || row.text.length > 1000 || typeof row.label !== 'string' || !row.label.trim() || row.label.length > 40)) throw new Error('需要 8–500 条样本，每条包含 text 和 label；每条文字不超过 1000 字，类别名不超过 40 字')
    samples.value = data.map(row => ({ text: row.text.trim(), label: row.label.trim() })); filename.value = file.name
  } catch (e) { if (active && generation === fileGeneration) error.value = e instanceof SyntaxError ? 'JSON 格式不正确，请检查示例文件的格式' : e instanceof Error ? e.message : '文件无法读取' }
}
function saveBlob(blob: Blob, filename: string) { const url = URL.createObjectURL(blob), link = document.createElement('a'); link.href = url; link.download = filename; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000) }
function example() {
  const topics = [['学习','今天学习英语单词','整理课程笔记','复习数学公式','练习英语语法','阅读计算机教材','写程序练习题'],['生活','今天去超市买菜','整理房间衣服','准备晚餐食材','去公园散步','预约周末聚餐','购买生活用品']]
  const rows = topics.flatMap(([label, ...texts]) => texts.map(text => ({text, label})))
  saveBlob(new Blob([JSON.stringify(rows, null, 2)], { type: 'application/json' }), 'training-example.json')
}
async function perform(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await action() } catch (e) { if (active) error.value = e instanceof Error ? e.message : '操作失败' }
  finally { if (active) busy.value = false }
}
function create() { return perform(async () => {
  const created = await personalApi.createTraining({ name: name.value, epochs: epochs.value, learningRate: learningRate.value, samples: samples.value, architecture:architecture.value,seed:seed.value,validationFraction:validationFraction.value })
  if (!active) return
  selected.value = created.id; prediction.value = []; await load()
}) }
function cancel(item: TrainingJob) { return perform(async () => { await personalApi.cancelTraining(item.id); if (active) await load() }) }
function remove(item: TrainingJob) {
  if (!window.confirm(`删除“${item.name}”及其样本、指标和模型？`)) return
  return perform(async () => { await personalApi.deleteTraining(item.id); if (active) { prediction.value = []; await load() } })
}
function predict(item: TrainingJob) { const id = item.id; return perform(async () => { const scores = await personalApi.predictTraining(id, input.value); if (active && selected.value === id) prediction.value = scores }) }
function download(item: TrainingJob) { return perform(async () => { const blob = await personalApi.downloadTraining(item.id); if (active) saveBlob(blob, `training-${item.id}.zip`) }) }
onMounted(() => load(true))
onBeforeUnmount(() => { active = false; fileGeneration++; clearTimeout(timer); samples.value = []; jobs.value = []; input.value = ''; prediction.value = [] })
</script>
<template>
  <section class="ai-training">
    <header class="ai-section-heading"><div><h3>训练一个自己的分类模型</h3><p>上传标注好的短文本，让模型学会区分类别。训练在此平台的服务器上运行，无需 API 密钥。</p></div><span>{{ loading ? '检查环境…' : environment ? `PyTorch ${environment.version} · CPU` : '环境未就绪' }}</span></header>
    <LoraColabCard />
    <p v-if="error" class="inline-alert inline-alert--error" role="alert">{{ error }} <button class="table-action" :disabled="busy" @click="load(true)">重新检查</button></p>
    <div class="training-workspace">
      <form class="training-setup" @submit.prevent="create">
        <div class="ai-list-heading"><h4>01 / 准备数据</h4><button type="button" class="table-action" @click="example">下载示例 ↗</button></div>
        <p>JSON 数组，每条填写 <code>text</code> 和 <code>label</code>。需要 2–12 个类别，每类至少 4 条不同样本。</p>
        <label class="training-file"><input type="file" accept=".json,application/json" :disabled="busy" @change="upload" /><span>{{ filename || '选择训练数据 JSON 文件' }}</span><small>8–500 条 · 最多 140 KB · 总文字不超过 30000 字</small></label>
        <div v-if="samples.length" class="training-data-summary"><strong>{{ samples.length }} 条样本 / {{ labels.length }} 个类别</strong><span>{{ labels.join(' · ') }}</span><p>{{ samples[0]?.text }} <small>→ {{ samples[0]?.label }}</small></p></div>
        <h4>02 / 设置训练</h4>
        <label class="field-label">实验名称<input v-model="name" class="field-input" maxlength="80" required :disabled="busy" /></label>
        <div class="training-parameters"><label class="field-label">模型结构<select v-model="architecture" class="field-input"><option value="mlp">双层神经网络</option><option value="linear">线性分类器</option></select></label><label class="field-label">随机种子<input v-model.number="seed" class="field-input" type="number" min="0" max="2147483647" required/></label><label class="field-label">验证集比例<select v-model="validationFraction" class="field-input"><option :value=".2">20%</option><option :value=".3">30%</option><option :value=".4">40%</option></select></label><label class="field-label">轮数<input v-model.number="epochs" class="field-input" type="number" min="1" max="50" required :disabled="busy" /></label><label class="field-label">学习率<input v-model.number="learningRate" class="field-input" type="number" min="0.0001" max="0.05" step="0.0001" required :disabled="busy" /></label></div>
        <p class="field-hint">约 20% 的样本留作验证，不参与训练。每个账户同时运行一个任务；单次最多 50 轮、5 分钟。</p>
        <button class="button button--dark" :disabled="busy || !environment || !samples.length || jobs.some(running)">{{ busy ? '处理中…' : '开始训练' }}</button>
      </form>
      <section class="training-results"><h4>03 / 我的训练</h4>
        <p v-if="!jobs.length" class="settings-empty">训练完成后，可在这里查看指标、输入文字试用，并下载模型和数据。</p>
        <div class="training-job-list"><button v-for="item in jobs" :key="item.id" type="button" :class="{ selected: selected === item.id }" :aria-pressed="selected === item.id" @click="choose(item.id)"><strong>{{ item.name }}</strong><span>{{ states[item.status] }} · {{ item.epoch }}/{{ item.epochs }} 轮</span></button></div>
        <article v-if="job" class="training-detail"><div class="ai-list-heading"><h4>{{ job.name }}</h4><span role="status">{{ states[job.status] }}</span></div><p>{{ job.message }}</p><p v-if="job.bestEpoch" class="field-hint">保留第 {{ job.bestEpoch }} 轮模型 · 验证 Macro-F1：{{ ((job.bestMacroF1||0)*100).toFixed(1) }}%</p><details v-if="job.confusionMatrix"><summary>查看验证混淆矩阵与数据指纹</summary><p>行：标准类别；列：预测类别。类别顺序：{{ job.labels.join("、") }}</p><pre>{{ job.confusionMatrix.map(r=>r.join("  ")).join("\n") }}</pre><code>{{ job.datasetHash }}</code></details><progress :value="job.epoch" :max="job.epochs" :aria-label="`已完成 ${job.epoch} / ${job.epochs} 轮`" /><div class="training-metrics"><div><small>训练损失</small><strong>{{ latest?.loss.toFixed(4) ?? '—' }}</strong></div><div><small>验证准确率</small><strong>{{ latest ? `${(latest.accuracy * 100).toFixed(1)}%` : '—' }}</strong></div><div><small>训练 / 验证样本</small><strong>{{ job.trainSamples ?? '—' }} / {{ job.validationSamples ?? '—' }}</strong></div></div>
          <details v-if="job.metrics.length"><summary>逐轮指标</summary><table class="training-metric-table"><thead><tr><th>轮次</th><th>训练损失</th><th>验证准确率</th></tr></thead><tbody><tr v-for="metric in job.metrics" :key="metric.epoch"><td>{{ metric.epoch }}</td><td>{{ metric.loss.toFixed(4) }}</td><td>{{ (metric.accuracy * 100).toFixed(1) }}%</td></tr></tbody></table></details>
          <form v-if="job.status === 'COMPLETED'" class="training-predict" @submit.prevent="predict(job)"><label class="field-label">试一段新文字<textarea v-model="input" class="field-input" rows="3" maxlength="1000" required :disabled="busy" placeholder="输入未出现在训练集里的文字" /></label><button class="button button--ghost" :disabled="busy || !input.trim()">预测类别</button><div v-if="prediction.length" aria-live="polite"><p v-for="row in prediction" :key="row.label"><strong>{{ row.label }}</strong> {{ (row.score * 100).toFixed(1) }}%</p><small>这是模型分类分数，不是经过校准的可信度。</small></div></form>
          <div class="settings-button-row"><button v-if="running(job)" class="button button--ghost" :disabled="busy || job.status === 'CANCELLING'" @click="cancel(job)">停止训练</button><button v-if="job.status === 'COMPLETED'" class="button button--ghost" :disabled="busy" @click="download(job)">下载模型与数据</button><button v-if="!running(job)" class="table-action note-danger" :disabled="busy" @click="remove(job)">删除任务</button></div>
        </article>
      </section>
    </div>
    <p class="field-hint training-footnote">当前模板是小型文本分类神经网络，适合学习训练流程与简单分类任务；不会修改第三方聊天模型，也不是大模型 LoRA 微调。记忆库与模型训练分别管理，私人记忆不会自动加入训练数据。</p>
  </section>
</template>
<style scoped>
.training-workspace{display:grid;grid-template-columns:minmax(0,.9fr) minmax(0,1.1fr);gap:32px;margin:28px 0}.training-setup{display:flex;flex-direction:column;gap:16px;align-self:start}.training-setup h4,.training-results>h4{margin:0}.training-setup p,.training-detail p{line-height:1.8;margin:0}.training-file{padding:24px;border:1px dashed var(--line);display:grid;gap:12px;background:var(--paper)}.training-file input{max-width:100%}.training-file small,.training-data-summary span{display:block;color:var(--muted,#758392);font-size:12px}.training-data-summary{padding:16px;background:var(--paper);border-left:2px solid var(--green);overflow-wrap:anywhere}.training-parameters{display:grid;grid-template-columns:1fr 1fr;gap:16px}.training-results{border-left:1px solid var(--line);padding-left:30px;min-width:0}.training-job-list{display:grid;gap:4px;margin:18px 0}.training-job-list strong{overflow-wrap:anywhere;min-width:0}.training-job-list button{display:flex;justify-content:space-between;gap:16px;background:none;border:0;border-left:2px solid transparent;padding:12px;text-align:left;color:inherit;cursor:pointer}.training-job-list button:hover,.training-job-list button.selected{background:var(--paper);border-left-color:var(--green)}.training-job-list span{font-size:12px;flex-shrink:0}.training-detail{display:grid;gap:20px;padding-top:12px}.training-detail progress{width:100%;height:5px;accent-color:var(--green)}.training-metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.training-metrics strong{display:block;font-size:22px;margin-top:8px;font-variant-numeric:tabular-nums}.training-metrics small{color:var(--muted,#758392)}.training-predict{display:grid;gap:14px;border-top:1px solid var(--line);padding-top:20px}.training-metric-table{width:100%;font-variant-numeric:tabular-nums}.training-metric-table th,.training-metric-table td{text-align:left;padding:8px;border-bottom:1px solid var(--line)}.training-footnote{border-top:1px solid var(--line);padding-top:20px}.ai-list-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}
</style>
