<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import { personalApi } from '@/api/personal'
import type { NoteAssistAction } from '@/types/api'
import type { ExperimentPreview, ExperimentSource, PersonalAiAction, PersonalAiPreview, PersonalAiProvider, PersonalAiResult, PersonalAiSetting } from '@/types/personal'
import { toastStore } from '@/stores/toast'
import { createRequestGuard, isPreviewExpired } from '@/lib/requestGuard'
import AppIcon from '@/components/AppIcon.vue'

const props = defineProps<{ title: string; body: string; disabled?: boolean }>()
const emit = defineEmits<{ append: [text: string]; replace: [text: string]; tags: [items: string[]] }>()
const sources = ref<ExperimentSource[]>([]), selectedIds = ref<string[]>([])
const sourceOpen = ref(false), sourcesLoading = ref(false), sourceBusy = ref(false)
const sourceError = ref(''), sourcePreview = ref<ExperimentPreview | null>(null)
const providers = ref<PersonalAiProvider[]>([])
const settings = ref<PersonalAiSetting[]>([]), settingsLoading = ref(false), settingsError = ref('')
const mode = ref('LOCAL_RULES'), includeSources = ref(false)
const busyAction = ref<PersonalAiAction | null>(null), phase = ref<'preview' | 'execute' | 'local' | null>(null)
const aiError = ref(''), review = ref<PersonalAiPreview | null>(null), consent = ref(false)
const result = ref<PersonalAiResult | null>(null)
const sourceGuard = createRequestGuard(), listGuard = createRequestGuard(), aiGuard = createRequestGuard(), settingGuard = createRequestGuard()
const now = ref(Date.now())
const clock = window.setInterval(() => { now.value = Date.now() }, 1000)
const usableSettings = computed(() => settings.value.filter(item => item.enabled && item.configured))
const remoteEnabled = computed(() => providers.value.find(item => item.provider === mode.value)?.remoteEnabled === true)
const selectedSetting = computed(() => usableSettings.value.find(item => item.provider === mode.value))
const activeContext = computed(() => JSON.stringify([props.title, props.body, mode.value, includeSources.value, selectedIds.value]))
const expired = computed(() => review.value ? isPreviewExpired(review.value.expiresAt, now.value) : false)
const actions: Array<{ action: PersonalAiAction; label: string }> = [{ action: 'summarize', label: '摘要' }, { action: 'outline', label: '大纲' }, { action: 'tags', label: '标签' }, { action: 'tidy', label: '格式整理' }, { action: 'draft', label: '报告草稿' }]
const actionLabel = (action: string) => actions.find(item => item.action === action)?.label || action
function errorText(reason: unknown, fallback: string) { return reason instanceof ApiClientError ? reason.message : fallback }
function cancelAi() {
  aiGuard.cancel(); busyAction.value = null; phase.value = null
  review.value = null; result.value = null; consent.value = false
}
function cancelSource() { sourceGuard.cancel(); sourceBusy.value = false; sourcePreview.value = null }
watch(activeContext, () => { cancelAi(); aiError.value = '' }, { flush: 'sync' })
watch(selectedIds, () => { cancelSource(); sourceError.value = '' }, { deep: true, flush: 'sync' })
async function loadSettings() {
  const request = settingGuard.start(); settingsLoading.value = true; settingsError.value = ''
  try {
    const [rows, catalog] = await Promise.all([personalApi.settings(request.signal), personalApi.providers(request.signal)])
    if (request.current()) { settings.value = rows; providers.value = catalog; if (mode.value !== 'LOCAL_RULES' && !usableSettings.value.some(item => item.provider === mode.value)) mode.value = 'LOCAL_RULES' }
  } catch (reason) { if (request.current()) settingsError.value = errorText(reason, '个人模型配置加载失败') }
  finally { if (request.current()) settingsLoading.value = false }
}
async function loadSources() {
  if (sourcesLoading.value) return
  const request = listGuard.start(); sourceOpen.value = true; sourcesLoading.value = true; sourceError.value = ''
  try {
    const rows = await personalApi.experiments(request.signal)
    if (request.current()) { sources.value = rows; selectedIds.value = selectedIds.value.filter(id => rows.some(row => row.taskId === id)) }
  } catch (reason) { if (request.current()) sourceError.value = errorText(reason, '实验结果加载失败') }
  finally { if (request.current()) sourcesLoading.value = false }
}
function closeSources() { listGuard.cancel(); sourcesLoading.value = false; cancelSource(); sourceOpen.value = false }
async function previewSources() {
  if (!selectedIds.value.length || selectedIds.value.length > 20 || sourceBusy.value || props.disabled) return
  const request = sourceGuard.start(); sourceBusy.value = true; sourcePreview.value = null; sourceError.value = ''
  try {
    const data = await personalApi.experimentPreview([...selectedIds.value], request.signal)
    if (request.current()) sourcePreview.value = data
  } catch (reason) { if (request.current()) sourceError.value = errorText(reason, '实验预览失败') }
  finally { if (request.current()) sourceBusy.value = false }
}
function insertSources() {
  if (!sourcePreview.value || props.disabled) return
  const text = sourcePreview.value.markdown
  cancelSource(); emit('append', text); toastStore.success('实验结果已插入编辑区，请保存笔记')
}
async function startAssist(action: PersonalAiAction) {
  if (busyAction.value || props.disabled) return
  if (!props.body.trim() && !(includeSources.value && selectedIds.value.length && mode.value !== 'LOCAL_RULES')) { aiError.value = '请先输入笔记正文，或选择要发送的实验结果。'; return }
  if (mode.value === 'LOCAL_RULES' && action === 'draft') return
  if (mode.value !== 'LOCAL_RULES' && !selectedSetting.value) { aiError.value = '请先保存并启用你自己的 AI 配置。'; return }
  if (props.body.length > 24000 && mode.value !== 'LOCAL_RULES') { aiError.value = '个人 AI 单次正文最多 24,000 个字符。请缩减正文后重新预览。'; return }
  cancelAi(); aiError.value = ''
  const request = aiGuard.start(); busyAction.value = action
  try {
    if (mode.value === 'LOCAL_RULES') {
      phase.value = 'local'
      const data = await api.assistDraft({ action: action as NoteAssistAction, body: props.body, title: props.title }, request.signal)
      if (request.current()) result.value = data
    } else {
      phase.value = 'preview'
      const data = await personalApi.preview({ provider: mode.value, action, title: props.title, body: props.body, selectedTaskIds: includeSources.value ? [...selectedIds.value] : [] }, request.signal)
      if (request.current()) { review.value = data; consent.value = false }
    }
  } catch (reason) { if (request.current()) aiError.value = errorText(reason, '整理准备失败，请重试') }
  finally { if (request.current()) { busyAction.value = null; phase.value = null } }
}
async function execute() {
  if (!review.value || !remoteEnabled.value || !consent.value || expired.value || busyAction.value || props.disabled) return
  const confirmed = review.value
  const request = aiGuard.start(); busyAction.value = confirmed.action; phase.value = 'execute'; aiError.value = ''
  // A token is consumed at most once: hide it before initiating transport; never auto-retry.
  review.value = null; consent.value = false
  try {
    const data = await personalApi.execute(confirmed.previewToken, request.signal)
    if (request.current()) result.value = data
  } catch (reason) {
    if (request.current()) aiError.value = errorText(reason, '未能取得模型结果。请求可能已发送并产生费用，请先检查使用记录；如需重试，请重新预览并确认。')
  } finally { if (request.current()) { busyAction.value = null; phase.value = null } }
}
function applyResult() {
  const data = result.value
  if (!data || props.disabled) return
  result.value = null
  if (data.action === 'tags') emit('tags', data.items)
  else if (data.action === 'tidy') emit('replace', data.result)
  else emit('append', `## ${actionLabel(data.action)}\n\n${data.result}`)
  toastStore.success('已应用到编辑区，请检查并保存')
}
onMounted(loadSettings)
onBeforeUnmount(() => { cancelAi(); cancelSource(); listGuard.cancel(); settingGuard.cancel(); window.clearInterval(clock) })
</script>

<template>
  <section class="panel note-personal-tools">
    <header class="panel-header"><div><p class="page-kicker">YOUR EXPERIMENTS</p><h3>把实验结果写进笔记</h3><p>只选择你自己的结果。先预览，再插入，不会自动保存笔记。</p></div><button class="button button--ghost button--small" :disabled="disabled" @click="sourceOpen ? closeSources() : loadSources()"><AppIcon name="plus" :size="15" />{{ sourceOpen ? '收起' : '选择实验结果' }}</button></header>
    <div v-if="sourceOpen" class="note-tool-content">
      <div class="settings-button-row"><span>已选 {{ selectedIds.length }} / 20</span><button class="table-action" :disabled="sourcesLoading" @click="loadSources">刷新列表</button><button class="table-action" :disabled="!selectedIds.length" @click="selectedIds = []">清空选择</button></div>
      <p v-if="sourceError" class="inline-alert inline-alert--error" role="alert">{{ sourceError }}</p>
      <p v-if="sourcesLoading" role="status">正在加载我的实验…</p><p v-else-if="!sources.length && !sourceError" class="settings-empty">还没有可用的实验结果。完成实验后，可以回到这里插入。</p>
      <div v-else class="note-source-list"><label v-for="source in sources" :key="source.taskId" class="note-source-option"><input v-model="selectedIds" type="checkbox" :value="source.taskId" :disabled="disabled || (!selectedIds.includes(source.taskId) && selectedIds.length >= 20)" /><span><strong>{{ source.title }}</strong><small>{{ source.modelName }} · {{ source.status }} · {{ new Date(source.createdAt).toLocaleString('zh-CN') }}</small><small>任务 {{ source.taskId }}</small></span></label></div>
      <div class="settings-button-row"><button class="button button--ghost" :disabled="disabled || !selectedIds.length || sourceBusy" @click="previewSources">{{ sourceBusy ? '准备预览…' : '预览选中结果' }}</button><button v-if="sourceBusy" class="button button--ghost" @click="cancelSource">取消</button></div>
      <div v-if="sourcePreview" class="note-outbound-review"><h4>即将插入笔记的内容</h4><pre>{{ sourcePreview.markdown }}</pre><div class="settings-button-row"><button class="button button--dark" :disabled="disabled" @click="insertSources">插入编辑区</button><button class="button button--ghost" @click="cancelSource">取消预览</button></div></div>
    </div>
  </section>
  <section class="panel note-personal-tools">
    <header class="panel-header"><div><p class="page-kicker">NOTE ASSISTANT</p><h3>整理与写作</h3><p>选择本地规则或你自己的模型。生成结果先预览，再决定是否应用。</p></div><RouterLink to="/app/settings?section=ai" class="settings-inline-link">管理个人 AI →</RouterLink></header>
    <div class="note-tool-content">
      <div class="note-assist-selection"><label class="field-label">处理方式<select v-model="mode" class="field-input" :disabled="disabled || !!busyAction || settingsLoading"><option value="LOCAL_RULES">本地规则 · 无外部模型调用</option><option v-for="item in usableSettings" :key="item.provider" :value="item.provider">个人 {{ item.provider }} · {{ item.model }}</option></select></label><button class="button button--ghost button--small" :disabled="settingsLoading || !!busyAction" @click="loadSettings">刷新配置</button></div>
      <p v-if="settingsError" class="inline-alert inline-alert--error" role="alert">{{ settingsError }}</p>
      <p v-if="mode === 'LOCAL_RULES'" class="field-hint">本地规则不会调用任何模型供应商，也不使用个人密钥。报告草稿需要选择个人模型。</p>
      <template v-else><p v-if="!remoteEnabled" class="inline-alert" role="status">此部署尚未开启外部模型调用。可以查看发送预览；确认发送目前不可用。请联系部署管理员启用。</p><p class="settings-notice">内容将离开本平台，发送到选定供应商。使用你个人 API 账户的额度并由供应商计费；不会回退到平台共享密钥。</p><label class="settings-check"><input v-model="includeSources" type="checkbox" :disabled="!!busyAction || !selectedIds.length" /> 同时发送上方选中的 {{ selectedIds.length }} 份实验结果</label><small v-if="includeSources && selectedIds.length">已选实验将追加在笔记正文之外；若正文已插入相同实验，可以取消勾选以避免重复发送。</small></template>
      <div class="note-ai-actions"><button v-for="item in actions" :key="item.action" class="button button--ghost button--small" :disabled="disabled || !!busyAction || (mode === 'LOCAL_RULES' && item.action === 'draft')" @click="startAssist(item.action)">{{ busyAction === item.action ? phase === 'execute' ? '模型生成中…' : '准备中…' : item.label }}</button><button v-if="busyAction" class="button button--ghost button--small" @click="cancelAi">停止等待</button></div>
      <p v-if="phase === 'execute'" class="field-hint" role="status">请求已开始。停止等待或离开页面不能保证撤销供应商已接收的请求，仍可能产生费用。</p>
      <p v-if="aiError" class="inline-alert inline-alert--error" role="alert">{{ aiError }}</p>
      <section v-if="review" class="note-outbound-review" aria-label="发送前确认">
        <h4>发送前，请确认这一次请求</h4><dl class="settings-facts"><dt>操作</dt><dd>{{ actionLabel(review.action) }}</dd><dt>供应商 / 模型</dt><dd>{{ review.provider }} / {{ review.model }}</dd><dt>实际端点</dt><dd>{{ review.endpoint }}</dd><dt>内容大小 / 到期</dt><dd>{{ review.outboundBytes.toLocaleString() }} 字节 · {{ new Date(review.expiresAt).toLocaleTimeString('zh-CN') }}</dd></dl>
        <h5>系统指令</h5><pre>{{ review.systemPrompt }}</pre><h5>完整发送内容</h5><pre>{{ review.context }}</pre>
        <p class="field-hint">这里只做预览，尚未调用模型。更改笔记、供应商或选择的实验会使这份预览失效。</p><p v-if="expired" class="inline-alert inline-alert--error" role="alert">预览已过期，请取消后重新选择操作。</p>
        <label class="settings-check"><input v-model="consent" type="checkbox" :disabled="expired" /> 我确认将以上内容发送到 {{ review.provider }}，并使用我的 API 密钥及供应商额度。</label>
        <div class="settings-button-row"><button class="button button--dark" :disabled="!remoteEnabled || !consent || expired || !!busyAction || disabled" @click="execute">确认发送并生成</button><button class="button button--ghost" @click="cancelAi">取消，不发送</button></div>
      </section>
      <section v-if="result" class="note-outbound-review" aria-label="生成结果预览"><div class="settings-button-row"><h4>{{ actionLabel(result.action) }} · {{ result.engine === 'LOCAL_RULES' ? '本地规则算法' : result.engine.replace('PERSONAL_AI:', '个人模型 ') }}</h4><button class="table-action" @click="result = null">关闭</button></div><p class="field-hint">尚未修改笔记。模型输出可能出错，请核对实验事实后应用。</p><ul v-if="result.action === 'tags'"><li v-for="(item, i) in result.items" :key="i">{{ item }}</li></ul><pre v-else>{{ result.result }}</pre><p v-if="result.note" class="field-hint">{{ result.note }}</p><button class="button button--dark" :disabled="disabled" @click="applyResult">{{ result.action === 'tidy' ? '用此结果替换正文' : result.action === 'tags' ? '合并标签' : '插入到文末' }}</button></section>
    </div>
  </section>
</template>
