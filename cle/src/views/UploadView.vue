<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type { FileView, ModelRuntimeView, ModelView, TaskType } from '@/types/api'
import UploadDropzone from '@/components/UploadDropzone.vue'
import AppIcon from '@/components/AppIcon.vue'
import { toastStore } from '@/stores/toast'

const route = useRoute()
const router = useRouter()
const models = ref<ModelView[]>([])
const runtime = ref<ModelRuntimeView | null>(null)
const selectedFile = ref<File | null>(null)
const uploadedFile = ref<FileView | null>(null)
const previewUrl = ref('')
const taskType = ref<TaskType>('LICENSE_PLATE')
const modelId = ref<number | null>(null)
const enhancementEnabled = ref(true)
const busyStage = ref<'idle' | 'uploading' | 'queued' | 'running'>('idle')
const error = ref('')

const availableModels = computed(() => models.value.filter(model => model.taskType === taskType.value))
const selectedMediaIsVideo = computed(() => selectedFile.value
  ? selectedFile.value.type.startsWith('video/')
  : uploadedFile.value?.contentType.startsWith('video/') === true)
const mediaMatchesTask = computed(() => selectedMediaIsVideo.value
  ? taskType.value === 'VIDEO_ANALYSIS'
  : taskType.value !== 'VIDEO_ANALYSIS')
const canRun = computed(() => Boolean(
  (selectedFile.value || uploadedFile.value)
  && modelId.value
  && mediaMatchesTask.value
  && busyStage.value === 'idle',
))

onMounted(async () => {
  try {
    const [modelList, runtimeInfo] = await Promise.all([api.models(), api.modelRuntime()])
    models.value = modelList
    runtime.value = runtimeInfo
    const requested = Number(route.query.model)
    const requestedModel = models.value.find(model => model.id === requested)
    if (requestedModel) {
      taskType.value = requestedModel.taskType
      modelId.value = requestedModel.id
    } else {
      modelId.value = availableModels.value[0]?.id || null
    }
  } catch (reason) {
    error.value = reason instanceof ApiClientError ? reason.message : '模型列表加载失败'
  }
})

watch(taskType, () => {
  if (!availableModels.value.some(model => model.id === modelId.value)) {
    modelId.value = availableModels.value[0]?.id || null
  }
})

onBeforeUnmount(() => { if (previewUrl.value) URL.revokeObjectURL(previewUrl.value) })

function handleFile(file: File) {
  error.value = ''
  if (!['image/jpeg', 'image/png', 'image/webp', 'video/mp4', 'video/webm'].includes(file.type)) {
    error.value = '请选择 JPEG、PNG、WEBP、MP4 或 WEBM 文件'
    return
  }
  if (file.size > 20 * 1024 * 1024) {
    error.value = '媒体文件不能超过 20 MB'
    return
  }
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  selectedFile.value = file
  taskType.value = file.type.startsWith('video/') ? 'VIDEO_ANALYSIS' : taskType.value === 'VIDEO_ANALYSIS' ? 'LICENSE_PLATE' : taskType.value
  uploadedFile.value = null
  previewUrl.value = URL.createObjectURL(file)
}

async function run() {
  if (!canRun.value || !modelId.value) return
  error.value = ''
  try {
    let file = uploadedFile.value
    if (!file && selectedFile.value) {
      busyStage.value = 'uploading'
      file = await api.upload(selectedFile.value)
      uploadedFile.value = file
    }
    if (!file) return
    busyStage.value = 'queued'
    let task = await api.run({ fileId: file.id, modelId: modelId.value, taskType: taskType.value, enhancementEnabled: enhancementEnabled.value })
    for (let attempt = 0; attempt < 160 && (task.status === 'PENDING' || task.status === 'RUNNING'); attempt++) {
      busyStage.value = task.status === 'PENDING' ? 'queued' : 'running'
      await new Promise(resolve => window.setTimeout(resolve, 1200))
      task = await api.task(task.id)
    }
    if (task.status === 'FAILED') throw new ApiClientError('INFERENCE_FAILED', task.errorMessage || '模型任务执行失败', 500, task.traceId)
    if (task.status !== 'COMPLETED') throw new ApiClientError('INFERENCE_TIMEOUT', '任务仍在后台运行，可稍后到 Logs 查看', 408, task.traceId)
    toastStore.success('实验已完成，正在打开对比结果')
    await router.push({ name: 'comparisons', query: { task: task.id } })
  } catch (reason) {
    error.value = reason instanceof ApiClientError ? `${reason.message}${reason.traceId ? ` · ${reason.traceId}` : ''}` : '实验运行失败'
  } finally {
    busyStage.value = 'idle'
  }
}
</script>

<template>
  <div class="page-stack">
    <section class="page-intro">
      <p class="page-kicker">新建实验</p>
      <h2>上传真实图片或视频，运行基线与优化策略。</h2>
      <p>图片进行固定视觉增强；视频对音轨做降噪与响度归一化。任务通过 Redis 排队，由独立 Worker 执行。</p>
    </section>

    <div class="experiment-builder">
      <section class="panel builder-upload">
        <div class="panel-header"><div><span class="step-pill">01</span><h3>输入样本</h3><p>图片文件会在服务端再次校验</p></div></div>
        <UploadDropzone v-if="!selectedFile" @selected="handleFile" />
        <div v-else class="selected-preview">
          <video v-if="selectedFile.type.startsWith('video/')" :src="previewUrl" controls playsinline muted />
          <img v-else :src="previewUrl" alt="待识别图片预览" />
          <div class="selected-file-info">
            <span class="file-avatar"><AppIcon name="file" :size="18" /></span>
            <div><strong>{{ selectedFile.name }}</strong><small>{{ (selectedFile.size / 1024 / 1024).toFixed(2) }} MB · {{ selectedFile.type }}</small></div>
            <button class="button button--ghost button--small" type="button" @click="selectedFile = null; uploadedFile = null">重新选择</button>
          </div>
        </div>
      </section>

      <section class="panel builder-config">
        <div class="panel-header"><div><span class="step-pill">02</span><h3>实验配置</h3><p>选择任务与固定模型版本</p></div></div>
        <div class="segmented-control">
          <button :class="{ active: taskType === 'LICENSE_PLATE' }" @click="taskType = 'LICENSE_PLATE'"><AppIcon name="model" :size="18" /> 车牌识别</button>
          <button :class="{ active: taskType === 'RECEIPT' }" @click="taskType = 'RECEIPT'"><AppIcon name="docs" :size="18" /> 票据识别</button>
          <button :class="{ active: taskType === 'VIDEO_ANALYSIS' }" @click="taskType = 'VIDEO_ANALYSIS'"><AppIcon name="video" :size="18" /> 视频分析</button>
        </div>
        <label class="field-label">模型版本
          <select v-model.number="modelId" class="field-input">
            <option v-for="model in availableModels" :key="model.id" :value="model.id">{{ model.name }} · v{{ model.version }}</option>
          </select>
        </label>
        <label class="toggle-row">
          <span><strong>启用质量感知优化</strong><small>{{ taskType === 'VIDEO_ANALYSIS' ? '生成降噪、响度归一化视频并做双路对照' : '运行基线与视觉增强双路对照' }}</small></span>
          <input v-model="enhancementEnabled" type="checkbox" /><i />
        </label>
        <div class="mode-notice" :class="{ 'mode-notice--live': runtime?.mode !== 'demo' && runtime?.credentialConfigured }"><AppIcon name="spark" :size="19" /><div><strong>{{ runtime?.mode === 'demo' ? '当前使用演示适配器' : '多供应商真实模型 Worker' }}</strong><p v-if="runtime?.mode !== 'demo'">根据所选模型安全调用 DeepSeek、Kimi 或千问；密钥不会发送到浏览器。</p><p v-else>用于验证业务链路，不代表真实模型性能。</p></div></div>
        <div v-if="(selectedFile || uploadedFile) && !mediaMatchesTask" class="inline-alert inline-alert--error">视频只能运行“视频分析”，图片请选择车牌或票据任务。</div>
        <div v-if="error" class="inline-alert inline-alert--error">{{ error }}</div>
        <button class="button button--dark button--full builder-submit" :disabled="!canRun" @click="run">
          <template v-if="busyStage === 'uploading'"><span class="button-spinner" /> 正在安全上传…</template>
          <template v-else-if="busyStage === 'queued'"><span class="button-spinner" /> 已入队，等待模型 Worker…</template>
          <template v-else-if="busyStage === 'running'"><span class="button-spinner" /> Worker 正在运行双路推理…</template>
          <template v-else>运行识别实验 <AppIcon name="arrow" :size="18" /></template>
        </button>
      </section>
    </div>

    <section class="process-preview">
      <div><span class="process-number">1</span><strong>安全入库</strong><small>文件头 · ClamAV · MinIO</small></div><AppIcon name="arrow" />
      <div><span class="process-number">2</span><strong>Redis 排队</strong><small>配额 · 限流 · trace_id</small></div><AppIcon name="arrow" />
      <div><span class="process-number">3</span><strong>Worker 优化</strong><small>图像增强或视频音轨降噪</small></div><AppIcon name="arrow" />
      <div><span class="process-number">4</span><strong>模型归档</strong><small>结果 · token · 人民币成本</small></div>
    </section>
  </div>
</template>
