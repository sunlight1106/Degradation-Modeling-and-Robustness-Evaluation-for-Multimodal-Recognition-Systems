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
    <section class="page-intro page-intro--split">
      <div>
        <p class="page-kicker">YOUR WORKSPACE / 工作空间</p>
        <h2>欢迎回来，{{ authStore.state.user?.displayName || '研究者' }}。</h2>
        <p>继续你的研究，或开启一个新的发现。</p>
      </div>
      <RouterLink v-if="authStore.has('experiment:run')" to="/app/upload" class="button button--dark"><AppIcon name="upload" :size="17" /> 新建识别实验</RouterLink>
    </section>

    <div class="workspace-feature-grid">
      <PortalBanner />
      <aside class="workspace-shortcuts" aria-label="常用入口">
        <header><span>QUICK ACCESS</span><h3>接下来，做点什么？</h3></header>
        <RouterLink v-if="authStore.has('knowledge:read')" to="/app/knowledge" class="workspace-shortcut" data-tone="cyan">
          <span class="shortcut-icon"><AppIcon name="book" :size="23" /></span><span><strong>探索知识库</strong><small>把线索，连成答案</small></span><AppIcon name="arrow" :size="17" />
        </RouterLink>
        <RouterLink v-if="authStore.has('note:read')" to="/app/notes" class="workspace-shortcut" data-tone="pink">
          <span class="shortcut-icon"><AppIcon name="note" :size="23" /></span><span><strong>整理我的笔记</strong><small>灵感与证据，都在这里</small></span><AppIcon name="arrow" :size="17" />
        </RouterLink>
        <RouterLink v-if="authStore.has('model:read')" to="/app/models" class="workspace-shortcut" data-tone="yellow">
          <span class="shortcut-icon"><AppIcon name="model" :size="23" /></span><span><strong>挑选识别模型</strong><small>找到适合这次任务的搭档</small></span><AppIcon name="arrow" :size="17" />
        </RouterLink>
        <footer><i /> 每一步探索，都值得记录。</footer>
      </aside>
    </div>

    <div v-if="loading" class="loading-grid"><span v-for="i in 4" :key="i" /></div>
    <div v-else-if="error" class="inline-alert inline-alert--error">{{ error }}</div>
    <section v-else-if="summary" class="metric-grid">
      <MetricCard label="实验总数" :value="summary.totalTasks" hint="当前可访问范围" />
      <MetricCard label="完成率" :value="`${summary.successRate.toFixed(1)}%`" :hint="`${summary.completedTasks} 次成功完成`" tone="green" />
      <MetricCard label="平均置信度变化" :value="`${(summary.averageConfidenceLift * 100).toFixed(1)}%`" hint="DEMO 为合成差值，不代表准确率" tone="green" />
      <MetricCard label="失败任务" :value="summary.failedTasks" hint="可按 trace_id 排查" :tone="summary.failedTasks ? 'amber' : 'default'" />
    </section>

    <section class="panel">
      <div class="panel-header">
        <div><h3>最近实验</h3><p>最近五次平台实验；当前新实验为 DEMO 合成结果</p></div>
        <RouterLink to="/app/comparisons" class="text-arrow">查看全部 <AppIcon name="arrow" :size="16" /></RouterLink>
      </div>
      <div v-if="loading" class="table-skeleton" />
      <EmptyState v-else-if="!summary?.recentTasks.length" title="还没有实验记录" description="完成一条 DEMO 实验后显示在这里；个人图片识别结果在上传页查看。" icon="spark">
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

    <section v-if="billing" class="home-balance-strip panel">
      <div><span class="wallet-icon"><AppIcon name="wallet" :size="22" /></span><span><small>平台沙箱余额</small><strong>{{ money(billing.wallet.balanceCny) }}</strong></span></div>
      <div class="home-quota"><span>本月配额 · 已用 {{ money(billing.wallet.monthSpentCny) }}</span><i><b :style="{ width: `${billing.wallet.quotaProgressPercent}%` }" /></i><strong>剩余 {{ money(billing.wallet.remainingQuotaCny) }}</strong></div>
      <RouterLink to="/app/billing" class="button button--ghost button--small">沙箱账本 <AppIcon name="arrow" :size="15" /></RouterLink>
    </section>

    <details v-if="providerBudgets.length" class="budget-ledger">
      <summary><span>历史预算记录<small>管理员可见 · 与个人 AI 账单分开</small></span><span class="budget-expand">查看明细 <AppIcon name="chevron" :size="14" /></span></summary>
      <div class="budget-body">
        <header><div><p class="page-kicker">ADMIN · LEGACY BUDGET</p><h3>本地预算与使用记录</h3><p>与个人 BYOK 用量和账单无关。金额为本地记录；供应商余额单独列出。</p></div><RouterLink to="/app/billing">打开平台账本 ↗</RouterLink></header>
        <div class="budget-table-wrap"><table class="budget-table" aria-label="历史供应商本地预算与供应商返回值">
          <thead><tr><th scope="col">供应商</th><th scope="col">本地已用</th><th scope="col">本地剩余</th><th scope="col">本地月度预算</th><th scope="col">供应商返回值</th></tr></thead>
          <tbody><tr v-for="provider in providerBudgets" :key="provider.provider">
            <th scope="row">{{ provider.displayName }}<small>{{ provider.configuredKeyCount ? '已保留历史配置' : '未配置历史密钥' }}</small></th>
            <td>{{ money(provider.usedCny) }}</td><td>{{ money(provider.remainingCny) }}</td><td>{{ money(provider.monthlyBudgetCny) }}</td>
            <td class="home-provider-values"><span v-if="provider.providerReportedBalance != null"><b>{{ provider.providerReportedBalance }}</b><small>币种待核对</small></span><span v-else class="budget-unavailable">未取得</span></td>
          </tr></tbody>
        </table></div>
        <p class="budget-footnote">本地记录每 15 秒刷新。历史配置仍可能查询旧余额接口；实际币种、额度和费用请到供应商账户核对。</p>
      </div>
    </details>
    <section class="overview-bottom-grid">
      <article class="panel quick-start">
        <div class="panel-header"><div><h3>DEMO 实验入门</h3><p>检查媒体、队列与报告链路</p></div><span class="step-count">4 steps</span></div>
        <ol>
          <li><span>1</span><div><strong>上传练习样本</strong><p>系统校验格式并记录文件指纹</p></div></li>
          <li><span>2</span><div><strong>选择演示任务</strong><p>当前旧实验仅生成 DEMO 结果</p></div></li>
          <li><span>3</span><div><strong>运行双路演示</strong><p>预处理成功不证明识别改善</p></div></li>
          <li><span>4</span><div><strong>对比并下载报告</strong><p>保留指标、结果与 trace_id</p></div></li>
        </ol>
      </article>
      <article class="panel research-note">
        <span class="note-icon"><AppIcon name="shield" :size="22" /></span>
        <p class="page-kicker">实验提醒</p>
        <h3>界面提升不等于论文结论</h3>
        <p>旧实验输出为 DEMO 合成结果，不能用于准确率或论文结论。个人图片识别需自己的视觉模型、部署远程开关和逐次确认；当前仅做模拟协议验证，真实视频暂未开放。</p>
        <RouterLink to="/app/docs" class="text-arrow">查看使用指南与边界 <AppIcon name="arrow" :size="16" /></RouterLink>
      </article>
    </section>
  </div>
</template>
