<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import { personalApi } from '@/api/personal'
import type { FileView } from '@/types/api'
import type { PersonalAiProvider, PersonalAiSetting, PersonalRecognitionTask, RecognitionPreview, RecognitionResult } from '@/types/personal'
import { createRequestGuard, isPreviewExpired } from '@/lib/requestGuard'
import { toastStore } from '@/stores/toast'

const catalog = ref<PersonalAiProvider[]>([]), settings = ref<PersonalAiSetting[]>([])
const provider = ref(''), taskType = ref<PersonalRecognitionTask>('IMAGE_UNDERSTANDING')
const question = ref('')
const file = ref<File | null>(null), uploaded = ref<FileView | null>(null), fileInput = ref<HTMLInputElement | null>(null)
const imageUrl = ref(''), review = ref<RecognitionPreview | null>(null), consent = ref(false), visionConfirmed = ref(false)
const busy = ref<'upload' | 'preview' | 'execute' | null>(null), loading = ref(false), error = ref('')
type SavedRecognitionResult = RecognitionResult & { id: string }
function isSavedResult(value: RecognitionResult): value is SavedRecognitionResult {
  return value.persistenceStatus === 'SAVED' && typeof value.id === 'string' && value.id.trim().length > 0
}
const results = ref<SavedRecognitionResult[]>([]), resultsError = ref(''), resultsLoading = ref(false), showResults = ref(false)
const result = ref<RecognitionResult | null>(null)
const resultSaved = computed(() => result.value !== null && isSavedResult(result.value))
const requestGuard = createRequestGuard(), loadGuard = createRequestGuard(), historyGuard = createRequestGuard()
const available = computed(() => settings.value.filter(item => item.configured && item.enabled && item.provider !== 'DEEPSEEK'))
const remoteEnabled = computed(() => catalog.value.find(item => item.provider === provider.value)?.remoteEnabled === true)
const selectedModel = computed(() => available.value.find(item => item.provider === provider.value)?.model)
const now = ref(Date.now()), timer = window.setInterval(() => { now.value = Date.now() }, 1000)
const expired = computed(() => review.value ? isPreviewExpired(review.value.expiresAt, now.value) : false)
const taskLabel = (type: string) => type === 'IMAGE_UNDERSTANDING' ? '图片理解' : type === 'RECEIPT' ? '票据识别' : '车牌识别'
function cancel() { requestGuard.cancel(); busy.value = null; review.value = null; consent.value = false }
watch([provider, taskType, file, question], () => { cancel(); result.value = null; error.value = '' }, { flush: 'sync' })
watch(provider, () => { visionConfirmed.value = false })
function chooseFile(event: Event) {
  const picked = (event.target as HTMLInputElement).files?.[0]
  if (!picked) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(picked.type) || picked.size > 5 * 1024 * 1024 || picked.size === 0) {
    if (imageUrl.value) URL.revokeObjectURL(imageUrl.value)
    file.value = null; uploaded.value = null; imageUrl.value = ''
    error.value = '请选择不超过 5 MB 的非空 JPEG、PNG 或 WEBP 图片。'; if (fileInput.value) fileInput.value.value = ''; return
  }
  if (imageUrl.value) URL.revokeObjectURL(imageUrl.value)
  file.value = picked; uploaded.value = null; imageUrl.value = URL.createObjectURL(picked)
}
async function load() {
  const request = loadGuard.start(); loading.value = true; error.value = ''
  try {
    const [providers, configs] = await Promise.all([personalApi.providers(request.signal), personalApi.settings(request.signal)])
    if (!request.current()) return
    catalog.value = providers; settings.value = configs
    if (!available.value.some(item => item.provider === provider.value)) provider.value = available.value[0]?.provider || ''
  } catch (reason) { if (request.current()) error.value = reason instanceof ApiClientError ? reason.message : '个人配置加载失败' }
  finally { if (request.current()) loading.value = false }
}
async function prepare() {
  if (busy.value || !file.value || !provider.value || !visionConfirmed.value) return
  cancel(); result.value = null; error.value = ''
  const request = requestGuard.start()
  try {
    let stored = uploaded.value
    if (!stored) {
      busy.value = 'upload'
      stored = await api.upload(file.value, request.signal)
      if (!request.current()) return
      uploaded.value = stored
    }
    busy.value = 'preview'
    const preview = await personalApi.recognitionPreview({ provider: provider.value, fileId: stored.id, taskType: taskType.value, question: question.value }, request.signal)
    if (request.current()) { review.value = preview; consent.value = false }
  } catch (reason) { if (request.current()) error.value = reason instanceof ApiClientError ? reason.message : '上传或准备预览失败，请重试' }
  finally { if (request.current()) busy.value = null }
}
async function execute() {
  if (!review.value || !consent.value || !visionConfirmed.value || !remoteEnabled.value || expired.value || busy.value) return
  const token = review.value.previewToken
  review.value = null; consent.value = false; error.value = ''; busy.value = 'execute'
  const request = requestGuard.start()
  try {
    const output = await personalApi.recognize(token, request.signal)
    if (!request.current()) return
    result.value = output
    if (isSavedResult(output)) {
      results.value = [output, ...results.value.filter(item => item.id !== output.id)].slice(0, 100)
      toastStore.success('识别结果已保存到你的账户，可在笔记中选择插入')
    }
  } catch (reason) {
    if (request.current()) error.value = reason instanceof ApiClientError ? reason.message : '未能取得结果。请求可能已发送并产生费用；请先刷新个人结果和使用记录，不要重复提交。'
  } finally { if (request.current()) busy.value = null }
}
async function loadResults() {
  const request = historyGuard.start(); showResults.value = true; resultsLoading.value = true; resultsError.value = ''
  try { const rows = await personalApi.recognitionResults(request.signal); if (request.current()) results.value = rows.filter(isSavedResult) }
  catch (reason) { if (request.current()) resultsError.value = reason instanceof ApiClientError ? reason.message : '历史结果加载失败' }
  finally { if (request.current()) resultsLoading.value = false }
}
onMounted(load)
onBeforeUnmount(() => { cancel(); loadGuard.cancel(); historyGuard.cancel(); window.clearInterval(timer); if (imageUrl.value) URL.revokeObjectURL(imageUrl.value) })
</script>
<template>
  <section class="panel note-personal-tools personal-recognition">
    <header class="panel-header"><div><p class="page-kicker">PERSONAL VISION</p><h3>个人 AI 图片识别</h3><p>理解图片、提取文字、解读图表，或针对图片提问。结果保存到你的账户。</p></div><RouterLink to="/app/settings?section=ai" class="settings-inline-link">配置个人 AI →</RouterLink></header>
    <div class="note-tool-content">
      <p class="settings-notice">图片会先上传到本平台。只有你确认后，原图及下方指令才会发送到选定供应商，并使用你的个人 API 额度。此入口暂不支持视频或 DeepSeek 视觉识别。</p>
      <p v-if="error" class="inline-alert inline-alert--error" role="alert">{{ error }}</p>
      <div class="settings-fields"><label class="field-label">我的供应商 / 模型<select v-model="provider" class="field-input" :disabled="loading || !!busy"><option value="" disabled>请选择已启用的个人配置</option><option v-for="item in available" :key="item.provider" :value="item.provider">{{ item.provider }} · {{ item.model }}</option></select></label><label class="field-label">识别任务<select v-model="taskType" class="field-input" :disabled="!!busy"><option value="IMAGE_UNDERSTANDING">图片内容理解</option><option value="RECEIPT">票据识别</option><option value="LICENSE_PLATE">车牌识别</option></select></label></div>
      <div class="settings-button-row"><button class="button button--ghost button--small" :disabled="loading || !!busy" @click="load">刷新个人配置</button><span v-if="loading" role="status">正在加载…</span><span v-else-if="!available.length">尚未启用个人配置，请先前往设置。</span></div>
      <label class="field-label">选择图片（JPEG / PNG / WEBP，最多 5 MB）<input ref="fileInput" class="field-input" type="file" accept="image/jpeg,image/png,image/webp" :disabled="!!busy" @change="chooseFile" /></label>
      <label class="field-label">想了解图片的什么内容？（可选）<textarea v-model="question" class="field-input" rows="3" maxlength="1000" :disabled="!!busy" placeholder="例如：提取图片中的文字；解释这张图表；描述照片中的场景。" /></label>
      <figure v-if="imageUrl && file" class="recognition-image"><img :src="imageUrl" alt="你选择的待识别图片" /><figcaption>{{ file.name }} · {{ (file.size / 1024).toFixed(1) }} KB</figcaption></figure>
      <label class="settings-check"><input v-model="visionConfirmed" type="checkbox" :disabled="!!busy || !provider" /> 我已确认模型 {{ selectedModel || '（未选择）' }} 支持图片输入。供应商可用性与权限未由平台验证。</label>
      <p v-if="provider && !remoteEnabled" class="inline-alert">此部署尚未开启外部模型调用。可上传及预览，实际发送需部署管理员启用。</p>
      <div class="settings-button-row"><button class="button button--dark" :disabled="!!busy || !file || !provider || !visionConfirmed" @click="prepare">{{ busy === 'upload' ? '上传到平台…' : busy === 'preview' ? '准备预览…' : '上传并预览发送内容' }}</button><button v-if="busy" class="button button--ghost" @click="cancel">停止等待</button></div>
      <p v-if="busy === 'execute'" class="field-hint" role="status">正在调用你的模型。停止等待或离开页面不能撤销供应商已接收的请求；结果可能仍会保存，请稍后刷新。</p>
      <section v-if="review" class="note-outbound-review" aria-label="图片发送前确认"><h4>确认发送这张图片及指令</h4><dl class="settings-facts"><dt>供应商 / 模型</dt><dd>{{ review.provider }} / {{ review.model }}</dd><dt>实际端点</dt><dd>{{ review.endpoint }}</dd><dt>原图</dt><dd>{{ review.fileName }} · {{ review.mime }} · {{ review.sizeBytes.toLocaleString() }} 字节</dd><dt>SHA-256</dt><dd>{{ review.sha256 }}</dd><dt>总发送大小</dt><dd>{{ review.outboundBytes.toLocaleString() }} 字节</dd><dt>有效期</dt><dd>{{ new Date(review.expiresAt).toLocaleTimeString('zh-CN') }}</dd></dl><h5>系统指令</h5><pre>{{ review.systemPrompt }}</pre><h5>用户指令</h5><pre>{{ review.prompt }}</pre><p class="field-hint">上方图片为将发送的原图。更换图片、供应商或任务会使本次预览失效。</p><p v-if="expired" class="inline-alert inline-alert--error">预览已过期，请重新准备。</p><label class="settings-check"><input v-model="consent" type="checkbox" :disabled="expired" /> 我允许将上述图片及完整指令发送到 {{ review.provider }}，并使用我的个人 API 额度。</label><div class="settings-button-row"><button class="button button--dark" :disabled="!consent || expired || !remoteEnabled || !visionConfirmed || !!busy" @click="execute">确认发送并识别</button><button class="button button--ghost" @click="cancel">取消，不发送</button></div></section>
      <section v-if="result" class="note-outbound-review" aria-label="图片识别结果"><div class="settings-button-row"><h4>{{ result.fileName }} · {{ taskLabel(result.taskType) }}</h4><button class="table-action" @click="result = null">收起</button></div><p v-if="!resultSaved" class="inline-alert inline-alert--error" role="alert">{{ result.warning || '模型已完成识别，但无法确认结果及用量记录已保存。请先复制下方内容；请求可能已产生费用，请勿重复发送。' }}</p><p class="field-hint">{{ result.provider }} / {{ result.model }} · {{ resultSaved ? '结果已保存。' : '仅在当前页面显示，请手动复制留存。尚未修改任何笔记。' }}请核对原图，模型输出可能不准确。</p><p class="field-hint">已报告 Tokens：{{ result.inputTokens ?? '未提供' }} / {{ result.outputTokens ?? '未提供' }}</p><pre>{{ result.result }}</pre><RouterLink v-if="resultSaved" to="/app/notes" class="settings-inline-link">前往笔记，选择这个结果插入 →</RouterLink></section>
      <div class="settings-button-row"><button class="button button--ghost" :disabled="resultsLoading" @click="loadResults">{{ resultsLoading ? '加载中…' : '查看 / 刷新我的识别结果' }}</button><button v-if="showResults" class="table-action" @click="showResults = false">收起历史</button></div><p v-if="resultsError" class="inline-alert inline-alert--error" role="alert">{{ resultsError }}</p>
      <div v-if="showResults" class="recognition-history"><p v-if="!resultsLoading && !results.length && !resultsError" class="settings-empty">还没有个人图片识别结果。</p><details v-for="item in results" :key="item.id" class="note-outbound-review"><summary>{{ item.fileName }} · {{ taskLabel(item.taskType) }} · {{ new Date(item.createdAt).toLocaleString('zh-CN') }}</summary><p class="field-hint">{{ item.provider }} / {{ item.model }} · 已报告 Tokens：{{ item.inputTokens ?? '未提供' }} / {{ item.outputTokens ?? '未提供' }}</p><pre>{{ item.result }}</pre></details></div>
    </div>
  </section>
</template>
