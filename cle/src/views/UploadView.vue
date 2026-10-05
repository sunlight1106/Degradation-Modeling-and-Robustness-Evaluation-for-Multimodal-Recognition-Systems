<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type { FileView, ModelRuntimeView, ModelView, TaskType } from '@/types/api'
import UploadDropzone from '@/components/UploadDropzone.vue'
import AppIcon from '@/components/AppIcon.vue'
import DocRow from '@/components/DocRow.vue'
import { toastStore } from '@/stores/toast'

const route = useRoute()
const router = useRouter()
const models = ref<ModelView[]>([])
const runtime = ref<ModelRuntimeView | null>(null)
const selectedFile = ref<File | null>(null)
const uploadedFile = ref<FileView | null>(null)
const previewUrl = ref('')
const taskOptions = [{ id: 'LICENSE_PLATE', title: '车牌识别', hint: 'LPR · 图片', icon: 'model' }, { id: 'RECEIPT', title: '票据识别', hint: 'OCR · 图片', icon: 'docs' }, { id: 'VIDEO_ANALYSIS', title: '视频分析', hint: 'VIDEO · 音视频', icon: 'video' }] as const
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
  <div class="lab-docs workspace-reader">
    <DocRow id="experiment" title="新建识别实验" intro>
      <p>从一个样本开始，观察模型看到了什么。</p>
      <p class="experiment-caption">上传素材、选择模型，再对照原始与优化后的结果。</p>
      <template #detail><nav class="experiment-index" aria-label="实验步骤"><a href="#sample">01 输入样本</a><a href="#configuration">02 选择模型</a><a href="#run">03 运行实验</a></nav></template>
    </DocRow>
    <DocRow id="sample" title="输入样本">
      <p>选择一张图片，或一段视频。你也可以把文件直接拖入右侧。</p>
      <p class="experiment-caption">JPEG、PNG、WEBP / MP4、WEBM<br />单个文件最大 20 MB</p>
      <template #detail>
        <UploadDropzone v-if="!selectedFile" @selected="handleFile" />
        <div v-else class="experiment-preview">
          <video v-if="selectedFile.type.startsWith('video/')" :src="previewUrl" controls playsinline muted />
          <img v-else :src="previewUrl" alt="待识别图片预览" />
          <div><span>{{ selectedFile.name }} <small>{{ (selectedFile.size / 1024 / 1024).toFixed(2) }} MB</small></span><button class="button" :disabled="busyStage !== 'idle'" @click="selectedFile = null; uploadedFile = null">重新选择</button></div>
        </div>
      </template>
    </DocRow>
    <DocRow id="configuration" title="模型与任务">
      <p>任务决定模型的识别目标。选择文件后，会自动匹配图片或视频任务。</p>
      <p><RouterLink to="/app/models">查看模型说明 ↗</RouterLink></p>
      <template #detail>
        <fieldset class="experiment-fields" :disabled="busyStage !== 'idle'">
          <legend>任务类型</legend>
          <div class="experiment-tasks">
            <button v-for="option in taskOptions" :key="option.id" type="button" :aria-pressed="taskType === option.id" @click="taskType = option.id"><span>{{ option.title }}</span><small>{{ option.hint }}</small></button>
          </div>
          <label class="field-label">识别模型<select v-model.number="modelId" class="field-input"><option v-if="!availableModels.length" :value="null" disabled>暂无可用模型</option><option v-for="model in availableModels" :key="model.id" :value="model.id">{{ model.name }} · v{{ model.version }}</option></select></label>
        </fieldset>
      </template>
    </DocRow>
    <DocRow id="run" title="运行与对照">
      <p>打开优化后，将对同一样本进行两路识别，便于比较处理前后的结果。</p>
      <p class="experiment-caption">优化效果取决于输入质量和模型能力。</p>
      <template #detail>
        <div class="experiment-run">
          <label class="experiment-check"><input v-model="enhancementEnabled" :disabled="busyStage !== 'idle'" type="checkbox" /><span>启用质量感知优化<small>{{ taskType === 'VIDEO_ANALYSIS' ? '视频降噪与音轨处理' : '原始图片与增强图片对照' }}</small></span></label>
          <p class="experiment-runtime">{{ runtime?.mode === 'demo' ? '演示模式 · 结果仅用于体验流程，不代表真实模型性能。' : runtime?.credentialConfigured ? '已连接模型服务，将按所选模型运行。' : '模型服务尚未就绪，请检查服务配置。' }}</p>
          <p v-if="(selectedFile || uploadedFile) && !mediaMatchesTask" class="inline-alert inline-alert--error">视频请选择“视频分析”，图片请选择车牌或票据任务。</p>
          <p v-if="error" class="inline-alert inline-alert--error" role="alert">{{ error }}</p>
          <div class="experiment-action">
            <button class="button button--dark" :disabled="!canRun" @click="run"><template v-if="busyStage !== 'idle'"><span class="button-spinner" />{{ busyStage === 'uploading' ? '正在上传…' : busyStage === 'queued' ? '等待处理…' : '正在识别…' }}</template><template v-else>运行实验 <AppIcon name="arrow" :size="16" /></template></button>
            <span v-if="!selectedFile && !uploadedFile">请先选择文件</span>
          </div>
        </div>
      </template>
    </DocRow>
  </div>
</template>