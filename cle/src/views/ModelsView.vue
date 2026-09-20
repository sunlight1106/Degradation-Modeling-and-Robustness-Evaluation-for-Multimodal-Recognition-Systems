<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useRoute } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type { ModelRuntimeView, ModelView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import { toastStore } from '@/stores/toast'
import SyntheticScene from '@/components/SyntheticScene.vue'
import { authStore } from '@/stores/auth'
import AppLogo from '@/components/AppLogo.vue'

const models = ref<ModelView[]>([])
const runtime = ref<ModelRuntimeView | null>(null)
const loading = ref(true)
const error = ref('')
const exporting = ref(false)
const route = useRoute()
const isPublic = computed(() => route.path === '/models')

onMounted(async () => {
  try {
    await authStore.ensureUser()
    const [modelList, runtimeInfo] = await Promise.all([api.models(), authStore.state.user ? api.modelRuntime() : Promise.resolve(null)])
    models.value = modelList
    runtime.value = runtimeInfo
  } catch (reason) {
    error.value = reason instanceof ApiClientError ? reason.message : '模型信息加载失败'
  } finally { loading.value = false }
})

async function exportDataset() {
  exporting.value = true
  try {
    await api.download('/api/v1/training/dataset', 'personal-platform-training-dataset.zip')
    toastStore.success('训练数据集已导出；请先人工复核标签')
  } catch (reason) {
    toastStore.error(reason instanceof ApiClientError ? reason.message : '数据集导出失败')
  } finally { exporting.value = false }
}

const taskVariant = (model: ModelView) => model.taskType === 'RECEIPT' ? 'receipt' : model.taskType === 'VIDEO_ANALYSIS' ? 'video' : 'plate'
const taskLabel = (model: ModelView) => model.taskType === 'LICENSE_PLATE' ? '车牌识别 · 质量分析' : model.taskType === 'RECEIPT' ? '票据 OCR · 字段抽取' : '视频理解 · 音轨降噪'
const modelTarget = (model: ModelView) => authStore.state.user ? `/app/upload?model=${model.id}` : '/login'
const providerName = (model: ModelView) => model.provider === 'DEEPSEEK' ? 'DeepSeek' : model.provider === 'KIMI' ? 'Kimi' : 'Qwen'
</script>

<template>
  <div :class="{ 'models-public-shell': isPublic }">
    <header v-if="isPublic" class="docs-public-nav"><RouterLink to="/"><AppLogo /></RouterLink><nav><RouterLink to="/">首页</RouterLink><a href="#catalog">模型</a><RouterLink to="/docs">文档</RouterLink></nav><div><span v-if="authStore.state.user" class="pp-current-user"><i>{{ authStore.state.user.displayName.slice(0, 1) }}</i>{{ authStore.state.user.displayName }}</span><RouterLink v-else to="/login">登录</RouterLink><RouterLink :to="authStore.state.user ? '/app/home' : '/login'" class="pp-enter-button">进入平台 <AppIcon name="arrow" :size="15" /></RouterLink></div></header>
  <div id="catalog" class="page-stack models-page" :class="{ 'models-page--public': isPublic }">
    <section class="page-intro page-intro--split">
      <div><p class="page-kicker">MODEL CATALOG</p><h2>Models</h2><p>选择任务模型，查看真实 API 能力、限制和训练数据准备状态。</p></div>
      <span class="runtime-pill" :class="{ ready: runtime?.credentialConfigured || !authStore.state.user }"><i />{{ runtime ? `${runtime.providers.filter(item => item.credentialConfigured).length}/3 providers ready` : '公开模型目录 · 登录后查看运行状态' }}</span>
    </section>

    <div v-if="loading" class="loading-grid"><span v-for="i in 3" :key="i" /></div>
    <div v-else-if="error" class="inline-alert inline-alert--error">{{ error }}</div>
    <EmptyState v-else-if="!models.length" title="暂无可用模型" description="管理员接入模型版本后会显示在这里。" icon="model" />

    <template v-else>
      <section class="recommended-section">
        <header><div><p class="page-kicker">RECOMMENDED</p><h3>Recommended models</h3></div><p>悬停卡片，查看输入示例与运行能力。</p></header>
        <div class="recommended-grid">
          <article class="model-utility-card">
            <RouterLink :to="authStore.state.user ? '/app/upload' : '/login'"><span><AppIcon name="spark" :size="22" /> 在 Playground 中运行</span><AppIcon name="arrow" :size="18" /></RouterLink>
            <RouterLink to="/docs#first-request"><span><AppIcon name="docs" :size="22" /> 阅读 API 文档</span><AppIcon name="arrow" :size="18" /></RouterLink>
            <button v-if="authStore.state.user" type="button" :disabled="exporting" @click="exportDataset"><span><AppIcon name="download" :size="22" /> {{ exporting ? '正在导出…' : '导出训练数据集' }}</span><AppIcon name="arrow" :size="18" /></button>
            <RouterLink v-else to="/register"><span><AppIcon name="users" :size="22" /> 免费创建账号</span><AppIcon name="arrow" :size="18" /></RouterLink>
          </article>

          <article v-for="model in models" :key="model.id" class="model-showcase-card" :class="`provider-${model.provider.toLowerCase()}`">
            <div class="model-showcase-image">
              <SyntheticScene :variant="taskVariant(model)" scale="md" code="苏C88R21" />
              <span class="model-scan-line" />
              <div class="model-detection-box" :class="{ receipt: model.taskType === 'RECEIPT', video: model.taskType === 'VIDEO_ANALYSIS' }"><i /><span>{{ model.taskType === 'LICENSE_PLATE' ? 'plate · 0.91' : model.taskType === 'RECEIPT' ? 'fields · 8' : 'events · 4' }}</span></div>
              <div class="model-hover-readout"><span><small>QUALITY</small><b>{{ model.taskType === 'LICENSE_PLATE' ? '0.71' : '0.84' }}</b></span><span><small>OUTPUT</small><b>JSON</b></span><span><small>MEDIA</small><b>{{ model.taskType === 'VIDEO_ANALYSIS' ? 'VIDEO' : 'IMAGE' }}</b></span></div>
            </div>
            <div class="model-showcase-top"><span class="model-cube"><AppIcon :name="model.taskType === 'VIDEO_ANALYSIS' ? 'video' : 'model'" :size="24" /></span><span class="model-badge">{{ providerName(model) }}</span></div>
            <div class="model-showcase-copy"><p>{{ taskLabel(model) }}</p><h3>{{ model.name }}</h3><span>{{ model.description }}</span></div>
            <div class="model-showcase-foot"><code>{{ model.code }}</code><span>v{{ model.version }}</span><span class="model-card-actions"><a :href="model.officialDocsUrl" target="_blank" rel="noreferrer">官方 API 文档 <AppIcon name="external" :size="14" /></a><RouterLink :to="modelTarget(model)">调用模型 <AppIcon name="arrow" :size="16" /></RouterLink></span></div>
          </article>
        </div>
      </section>

      <section class="model-capability-panel panel">
        <header class="panel-header"><div><p class="page-kicker">TRAINING & EVALUATION</p><h3>训练、评测与微调状态</h3><p>区分已经真正接通的能力与供应商尚未提供的能力。</p></div></header>
        <div class="model-capability-grid">
          <article class="is-ready"><span><AppIcon name="terminal" :size="22" /></span><div><small>01 · READY</small><h4>三家真实模型适配</h4><p>Java 后端统一调用 DeepSeek、Kimi 和千问，支持结构化 JSON 与密钥轮换。</p></div><i><AppIcon name="check" :size="16" /></i></article>
          <article class="is-ready"><span><AppIcon name="download" :size="22" /></span><div><small>02 · READY</small><h4>数据集与评测准备</h4><p>导出图片/视频、JSONL 标签、稳定 train/validation/test 划分和 dataset card。</p></div><i><AppIcon name="check" :size="16" /></i></article>
          <article class="is-ready"><span><AppIcon name="video" :size="22" /></span><div><small>03 · READY</small><h4>视频与杂音处理</h4><p>Kimi/千问支持视频理解，FFmpeg 对优化分支执行音轨降噪与响度归一化。</p></div><i><AppIcon name="check" :size="16" /></i></article>
        </div>
      </section>

      <section v-if="runtime?.limitations.length" class="model-limitations">
        <AppIcon name="shield" :size="20" /><div><strong>模型分析边界</strong><p v-for="item in runtime.limitations" :key="item">{{ item }}</p></div>
      </section>
    </template>
  </div></div>
</template>
