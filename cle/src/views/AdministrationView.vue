<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { adminApi, type Statistics, type Audit, type Page } from '@/api/admin'
import { ApiClientError } from '@/api/client'
import { authStore } from '@/stores/auth'
import AdminTrendChart from '@/components/AdminTrendChart.vue'
const range = ref(30), loading = ref(false), error = ref('')
const stats = ref<Statistics | null>(null), audit = ref<Page<Audit> | null>(null), page = ref(0)
const loadedRange = ref(30), loadedPage = ref(0)
let version = 0, active = true
const fmt = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
const bytes = (value: number) => `${(value / 1024 ** 2).toFixed(1)} MB`
const actions: Record<string, string> = { USER_CREATE: '创建用户', USER_UPDATE: '修改账号', USER_ACCESS: '调整使用权限', USER_SESSIONS: '撤销登录', ROLE_CREATE: '新建角色', ROLE_PERMISSIONS: '修改角色权限' }
async function load() {
  const current = ++version, requestedRange = range.value, requestedPage = page.value; loading.value = true; error.value = ''
  try {
    const [summary, records] = await Promise.all([authStore.has('admin:stats') ? adminApi.statistics(range.value) : Promise.resolve(null), authStore.has('admin:audit') ? adminApi.audit(page.value) : Promise.resolve(null)])
    if (active && current === version) { stats.value = summary; audit.value = records; loadedRange.value = requestedRange; loadedPage.value = requestedPage }
  } catch (reason) { if (active && current === version) error.value = reason instanceof ApiClientError ? reason.message : '管理数据加载失败' }
  finally { if (active && current === version) loading.value = false }
}
watch([range, page], load)
onMounted(load); onBeforeUnmount(() => { active = false; version++ })
</script>
<template>
  <div class="page-stack administration-page">
    <section class="page-intro page-intro--split"><div><p class="page-kicker">系统管理</p><h2>平台总览</h2><p>查看平台使用情况，管理账号和权限。</p></div><button class="button button--ghost" :disabled="loading" @click="load">{{ loading ? '更新中…' : '刷新数据' }}</button></section>
    <nav class="admin-shortcuts" aria-label="管理入口"><RouterLink v-if="authStore.has('user:read')" to="/app/users">用户与使用权限 <span>→</span></RouterLink><RouterLink v-if="authStore.has('role:read')" to="/app/roles">角色权限 <span>→</span></RouterLink><RouterLink v-if="authStore.has('billing:manage')" to="/app/billing">额度与预算 <span>→</span></RouterLink></nav>
    <p v-if="error" class="inline-alert inline-alert--error" role="alert">{{ error }}</p>
    <p v-if="loading && !stats && !audit" class="table-muted" role="status">正在读取管理数据…</p>
    <template v-if="stats">
      <section class="admin-metrics"><article><span>全部用户</span><strong>{{ stats.users.toLocaleString() }}</strong><small>{{ stats.enabledUsers }} 可用 · {{ stats.disabledUsers }} 停用 · {{ stats.expiredUsers }} 到期</small></article><article><span>最近活跃</span><strong>{{ stats.recentUsers.toLocaleString() }}</strong><small>最近 15 分钟访问过平台的用户</small></article><article><span>保存的笔记</span><strong>{{ stats.notes.toLocaleString() }}</strong><small>不含回收站 · {{ stats.roles }} 个角色</small></article><article><span>文件存储</span><strong>{{ bytes(stats.storageBytes) }}</strong><small>{{ stats.files }} 份文件</small></article><article><span>个人 AI 调用</span><strong>{{ stats.aiCalls.toLocaleString() }}</strong><small>累计已记录请求，含失败</small></article><article><span>识别实验</span><strong>{{ stats.experiments.toLocaleString() }}</strong><small>累计任务，含演示模式</small></article></section>
      <section class="panel admin-section"><header class="admin-section-header"><div><h3>使用趋势</h3><p>登录用户按每天去重；个人 AI 与识别实验分别统计。</p></div><label>时间范围<select v-model.number="range" class="compact-select"><option :value="7">最近 7 天</option><option :value="30">最近 30 天</option><option :value="90">最近 90 天</option></select></label></header><AdminTrendChart v-if="loadedRange === range" :days="stats.days" /><p v-else class="table-muted" role="status">{{ loading ? '正在读取此时间范围的数据…' : '此时间范围尚未加载，请刷新重试。' }}</p><p v-if="loadedRange === range" class="admin-footnote">按 UTC 自然日统计 · 更新于 {{ fmt(stats.generatedAt) }}。登录记录仅保留会话清理周期内的数据。</p></section>
    </template>
    <section v-if="audit" class="panel admin-section"><header class="admin-section-header"><div><h3>管理操作记录</h3><p>记录账号、权限和登录会话的管理操作。</p></div><span class="table-muted">{{ audit.total }} 条记录</span></header><p v-if="loadedPage !== page" class="admin-empty" role="status">{{ loading ? '正在读取此页记录…' : '此页尚未加载，请刷新重试。' }}</p><p v-else-if="!audit.items.length" class="admin-empty">暂无管理操作，后续修改会显示在这里。</p><div v-else class="data-table-wrap"><table class="data-table"><thead><tr><th>时间</th><th>操作人</th><th>操作</th><th>目标</th><th>详情</th></tr></thead><tbody><tr v-for="row in audit.items" :key="row.id"><td>{{ fmt(row.createdAt) }}</td><td>@{{ row.operatorName }}</td><td>{{ actions[row.action] || row.action }}</td><td>{{ row.targetType === 'USER' ? '用户' : '角色' }} #{{ row.targetId }}</td><td><details><summary>查看变更</summary><p class="audit-detail">{{ row.detail }}</p></details></td></tr></tbody></table></div><footer class="admin-pagination"><button class="button button--ghost" :disabled="page === 0 || loading" @click="page--">上一页</button><span>第 {{ page + 1 }} 页</span><button class="button button--ghost" :disabled="(page + 1) * audit.size >= audit.total || loading" @click="page++">下一页</button></footer></section>
  </div>
</template>
<style scoped>
.admin-shortcuts{display:flex;gap:32px;padding:14px 0 22px;border-bottom:1px solid var(--line)}.admin-shortcuts a{color:var(--ink-soft);text-decoration:none;font-size:14px}.admin-shortcuts a:hover{color:var(--green)}.admin-shortcuts span{padding-left:15px}.admin-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:28px 40px;margin:12px 0 28px}.admin-metrics article{padding:8px 0 22px;border-bottom:1px solid var(--line);display:flex;flex-direction:column;gap:8px}.admin-metrics span{font-size:13px;color:var(--ink-soft)}.admin-metrics strong{font-size:30px;line-height:1.4;letter-spacing:-.8px;font-weight:550;font-variant-numeric:tabular-nums}.admin-metrics small{color:var(--muted);font-size:12px}.admin-section{padding:26px 30px}.admin-section-header{display:flex;justify-content:space-between;align-items:center;gap:24px;margin-bottom:28px}.admin-section-header h3{font-size:19px;margin:0 0 8px}.admin-section-header p,.admin-section-header label{font-size:13px;color:var(--muted);margin:0}.admin-section-header select{margin-left:12px}.admin-footnote{font-size:12px;color:var(--muted);margin:18px 0 0}.admin-pagination{display:flex;justify-content:flex-end;gap:18px;align-items:center;margin-top:20px;color:var(--muted);font-size:13px}.admin-empty{color:var(--muted);padding:28px 0}.audit-detail{max-width:560px;white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}.data-table td{font-size:13px;vertical-align:top}.data-table details summary{cursor:pointer;color:var(--green)}
</style>
