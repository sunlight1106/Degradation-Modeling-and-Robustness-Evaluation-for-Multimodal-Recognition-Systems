<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type { BillingSummary, DashboardSummary, ProviderBudgetView } from '@/types/api'
import PortalBanner from '@/components/PortalBanner.vue'
import MetricCard from '@/components/MetricCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import EmptyState from '@/components/EmptyState.vue'
import AppIcon from '@/components/AppIcon.vue'
import { authStore } from '@/stores/auth'

const summary = ref<DashboardSummary | null>(null)
const loading = ref(true)
const error = ref('')
const billing = ref<BillingSummary | null>(null)
const providerBudgets = ref<ProviderBudgetView[]>([])
let refreshTimer = 0
let refreshing = false

async function load() {
  if (refreshing) return
  refreshing = true
  try {
    error.value = ''
    const [dashboard, wallet, providers] = await Promise.all([
      api.dashboard(), authStore.has('billing:read') ? api.billing() : Promise.resolve(null), authStore.has('billing:read:any') ? api.providerBudgets() : Promise.resolve([]),
    ])
    summary.value = dashboard
    billing.value = wallet
    providerBudgets.value = providers
  } catch (reason) {
    error.value = reason instanceof ApiClientError ? reason.message : '总览数据加载失败'
  } finally {
    loading.value = false
    refreshing = false
  }
}

onMounted(async () => { await load(); refreshTimer = window.setInterval(() => { if (document.visibilityState === 'visible') void load() }, 15000) })
onBeforeUnmount(() => window.clearInterval(refreshTimer))

const formatDate = (value: string) => new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
}).format(new Date(value))
const money = (value: number | null | undefined) => `¥${Number(value || 0).toFixed(2)}`
</script>

<template>
  <div class="page-stack portal-overview">
    <PortalBanner />
    <section class="page-intro page-intro--split">
      <div>
        <p class="page-kicker">LATEST / 工作台动态</p>
        <h2>每一次识别，都有迹可循。</h2>
        <p>查看当前实验状态、置信度变化和最近运行记录。</p>
      </div>
      <RouterLink to="/app/upload" class="button button--dark"><AppIcon name="upload" :size="17" /> 新建识别实验</RouterLink>
    </section>

    <div v-if="loading" class="loading-grid"><span v-for="i in 4" :key="i" /></div>
    <div v-else-if="error" class="inline-alert inline-alert--error">{{ error }}</div>
    <section v-else-if="summary" class="metric-grid">
      <MetricCard label="实验总数" :value="summary.totalTasks" hint="当前可访问范围" />
      <MetricCard label="完成率" :value="`${summary.successRate.toFixed(1)}%`" :hint="`${summary.completedTasks} 次成功完成`" tone="green" />
      <MetricCard label="平均置信度变化" :value="`${(summary.averageConfidenceLift * 100).toFixed(1)}%`" hint="模型自评变化，不等于准确率提升" tone="green" />
      <MetricCard label="失败任务" :value="summary.failedTasks" hint="可按 trace_id 排查" :tone="summary.failedTasks ? 'amber' : 'default'" />
    </section>

    <section v-if="billing" class="home-balance-strip panel">
      <div><span class="wallet-icon"><AppIcon name="wallet" :size="22" /></span><span><small>我的可用余额</small><strong>{{ money(billing.wallet.balanceCny) }}</strong></span></div>
      <div class="home-quota"><span>本月配额 · 已用 {{ money(billing.wallet.monthSpentCny) }}</span><i><b :style="{ width: `${billing.wallet.quotaProgressPercent}%` }" /></i><strong>剩余 {{ money(billing.wallet.remainingQuotaCny) }}</strong></div>
      <RouterLink to="/app/billing" class="button button--ghost button--small">查看或充值 <AppIcon name="arrow" :size="15" /></RouterLink>
    </section>

    <section v-if="providerBudgets.length" class="home-provider-section">
      <div class="panel-header"><div><p class="page-kicker">ADMIN · LIVE BUDGET</p><h3>模型 API 预算与余额</h3><p>本地用量和人民币预算每 15 秒更新；可用时同时展示供应商官方余额。</p></div><RouterLink to="/app/billing" class="text-arrow">完整账单 <AppIcon name="arrow" :size="16" /></RouterLink></div>
      <div class="home-provider-grid"><article v-for="provider in providerBudgets" :key="provider.provider" class="panel"><header><span>{{ provider.provider === 'DEEPSEEK' ? 'DS' : provider.provider === 'KIMI' ? 'K' : 'Q' }}</span><div><strong>{{ provider.displayName }}</strong><small>{{ provider.configuredKeyCount }} keys · round robin</small></div><i :class="{ ready: provider.configuredKeyCount }" /></header><div class="home-provider-values"><span><small>已用</small><b>{{ money(provider.usedCny) }}</b></span><span><small>预算剩余</small><b>{{ money(provider.remainingCny) }}</b></span><span><small>{{ provider.providerReportedBalance != null ? '官方余额' : '月度预算' }}</small><b>{{ money(provider.providerReportedBalance ?? provider.monthlyBudgetCny) }}</b></span></div><div class="provider-progress"><i :style="{ width: `${provider.progressPercent}%` }" /></div></article></div>
    </section>

    <section class="panel">
      <div class="panel-header">
        <div><h3>最近实验</h3><p>最近五次模型调用与运行状态</p></div>
        <RouterLink to="/app/comparisons" class="text-arrow">查看全部 <AppIcon name="arrow" :size="16" /></RouterLink>
      </div>
      <div v-if="loading" class="table-skeleton" />
      <EmptyState v-else-if="!summary?.recentTasks.length" title="还没有实验记录" description="上传一张图片并运行模型后，记录会出现在这里。" icon="spark">
        <RouterLink to="/app/upload" class="button button--ghost button--small">创建第一条实验</RouterLink>
      </EmptyState>
      <div v-else class="data-table-wrap">
        <table class="data-table">
          <thead><tr><th>任务</th><th>模型版本</th><th>状态</th><th>置信度</th><th>创建时间</th><th /></tr></thead>
          <tbody>
            <tr v-for="task in summary.recentTasks" :key="task.id">
              <td><div class="table-primary"><span class="file-avatar"><AppIcon :name="task.taskType === 'VIDEO_ANALYSIS' ? 'video' : task.taskType === 'LICENSE_PLATE' ? 'model' : 'docs'" :size="17" /></span><span><strong>{{ task.taskType === 'LICENSE_PLATE' ? '车牌识别' : task.taskType === 'RECEIPT' ? '票据识别' : '音视频分析' }}</strong><small>{{ task.inputFile.originalName }}</small></span></div></td>
              <td>{{ task.model.name }} <small class="table-muted">v{{ task.model.version }}</small></td>
              <td><StatusBadge :status="task.status" /></td>
              <td>{{ task.optimizedConfidence != null ? `${(task.optimizedConfidence * 100).toFixed(1)}%` : task.baselineConfidence != null ? `${(task.baselineConfidence * 100).toFixed(1)}%` : '—' }}</td>
              <td>{{ formatDate(task.createdAt) }}</td>
              <td><RouterLink :to="`/app/comparisons?task=${task.id}`" class="table-action">查看</RouterLink></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section class="overview-bottom-grid">
      <article class="panel quick-start">
        <div class="panel-header"><div><h3>快速开始</h3><p>一条完整评测链路</p></div><span class="step-count">4 steps</span></div>
        <ol>
          <li><span>1</span><div><strong>上传真实输入</strong><p>系统校验格式并记录文件指纹</p></div></li>
          <li><span>2</span><div><strong>选择任务与模型</strong><p>固定任务类型及模型版本</p></div></li>
          <li><span>3</span><div><strong>运行基线与优化</strong><p>保持输入与模型不变</p></div></li>
          <li><span>4</span><div><strong>对比并下载报告</strong><p>保留指标、结果与 trace_id</p></div></li>
        </ol>
      </article>
      <article class="panel research-note">
        <span class="note-icon"><AppIcon name="shield" :size="22" /></span>
        <p class="page-kicker">实验提醒</p>
        <h3>界面提升不等于论文结论</h3>
        <p>真实模型已经接入，但仍需固定人工真值、数据集、退化参数和评测脚本，前后差异才具备研究证据价值。</p>
        <RouterLink to="/app/docs" class="text-arrow">查看评测规范 <AppIcon name="arrow" :size="16" /></RouterLink>
      </article>
    </section>
  </div>
</template>
