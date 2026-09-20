<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, ApiClientError } from '@/api/client'
import type { AdminWalletView, BillingSummary, ModelProvider, PaymentMethod, ProviderBudgetView, ProviderCredentialView, RechargeOrderView } from '@/types/api'
import { authStore } from '@/stores/auth'
import { toastStore } from '@/stores/toast'
import AppIcon from '@/components/AppIcon.vue'
import QrCode from '@/components/QrCode.vue'

const summary = ref<BillingSummary | null>(null)
const providers = ref<ProviderBudgetView[]>([])
const wallets = ref<AdminWalletView[]>([])
const credentials = ref<ProviderCredentialView[]>([])
const loading = ref(true)
const error = ref('')
const amount = ref(50)
const method = ref<PaymentMethod>('ALIPAY')
const cardNumber = ref('')
const expiry = ref('')
const cvv = ref('')
const phone = ref('')
const pending = ref<RechargeOrderView | null>(null)
const verificationCode = ref('')
const busy = ref(false)
const successAmount = ref(0)
const editingWallet = ref<AdminWalletView | null>(null)
const balanceDelta = ref(0)
const quota = ref(0)
const adjustmentReason = ref('管理员人工调度')
const credentialProvider = ref<ModelProvider>('DEEPSEEK')
const credentialLabel = ref('primary')
const credentialKey = ref('')
let refreshTimer = 0
let paymentTimer = 0

const isAdmin = computed(() => authStore.has('billing:read:any'))
const canManage = computed(() => authStore.has('billing:manage'))

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const requests: Promise<unknown>[] = [api.billing()]
    if (isAdmin.value) requests.push(api.providerBudgets())
    if (canManage.value) requests.push(api.adminWallets(), api.credentials())
    const values = await Promise.all(requests)
    summary.value = values[0] as BillingSummary
    if (isAdmin.value) providers.value = values[1] as ProviderBudgetView[]
    if (canManage.value) { wallets.value = values[2] as AdminWalletView[]; credentials.value = values[3] as ProviderCredentialView[] }
  } catch (reason) { if (!silent) error.value = reason instanceof ApiClientError ? reason.message : '账单数据加载失败' }
  finally { if (!silent) loading.value = false }
}

onMounted(async () => { await load(); refreshTimer = window.setInterval(() => load(true), 15000) })
onBeforeUnmount(() => { window.clearInterval(refreshTimer); window.clearInterval(paymentTimer) })
function normalizeCard() { cardNumber.value = cardNumber.value.replace(/\D/g, '').slice(0, 19).replace(/(.{4})/g, '$1 ').trim() }

function startPaymentPolling() {
  window.clearInterval(paymentTimer)
  paymentTimer = window.setInterval(async () => {
    if (!pending.value || pending.value.method === 'BANK_CARD') return
    try {
      const current = await api.recharge(pending.value.id)
      pending.value = current
      if (current.status === 'PAID') { successAmount.value = current.amount; pending.value = null; window.clearInterval(paymentTimer); await load(true); toastStore.success('扫码支付已到账') }
      else if (['EXPIRED', 'CANCELLED'].includes(current.status)) { window.clearInterval(paymentTimer); error.value = '扫码订单已过期，请重新创建' }
    } catch { /* 轮询失败不打断当前界面 */ }
  }, 2000)
}

async function createOrder() {
  error.value = ''
  if (amount.value < 1 || amount.value > 10000) { error.value = '充值金额需在 1 到 10000 元之间'; return }
  let last4: string | undefined
  if (method.value === 'BANK_CARD') {
    const digits = cardNumber.value.replace(/\D/g, '')
    if (digits.length < 12 || !/^\d{2}\/\d{2}$/.test(expiry.value) || !/^\d{3,4}$/.test(cvv.value)) { error.value = '请完整填写有效的银行卡信息'; return }
    if (!/^1[3-9]\d{9}$/.test(phone.value)) { error.value = '请输入用于接收短信验证码的手机号'; return }
    last4 = digits.slice(-4)
  }
  busy.value = true
  try {
    pending.value = await api.createRecharge({ amount: amount.value, method: method.value, bankLast4: last4, phone: method.value === 'BANK_CARD' ? phone.value : undefined })
    cardNumber.value = ''; expiry.value = ''; cvv.value = ''; verificationCode.value = ''
    if (method.value === 'BANK_CARD') toastStore.success(`验证码已发送至 ${pending.value.phoneMasked}`)
    else { toastStore.success('扫码订单已创建'); startPaymentPolling() }
  } catch (reason) { error.value = reason instanceof ApiClientError ? reason.message : '订单创建失败' }
  finally { busy.value = false }
}

async function confirmOrder() {
  if (!pending.value || !/^\d{6}$/.test(verificationCode.value)) return
  busy.value = true; error.value = ''
  try { const paid = await api.confirmRecharge(pending.value.id, verificationCode.value); successAmount.value = paid.amount; pending.value = null; verificationCode.value = ''; await load(true); toastStore.success('验证码正确，充值成功') }
  catch (reason) {
    error.value = reason instanceof ApiClientError ? reason.message : '验证码确认失败'
    if (pending.value) try { pending.value = await api.recharge(pending.value.id) } catch { /* 保留原状态 */ }
  } finally { busy.value = false }
}

async function saveProvider(provider: ProviderBudgetView) {
  try { await api.updateProviderBudget(provider.provider, provider.monthlyBudgetCny); toastStore.success('供应商预算已更新'); await load(true) }
  catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '预算更新失败') }
}
function editWallet(item: AdminWalletView) { editingWallet.value = item; balanceDelta.value = 0; quota.value = item.wallet.monthlyQuotaCny }
async function saveWallet() {
  if (!editingWallet.value) return
  try { await api.adjustWallet(editingWallet.value.userId, { balanceDelta: balanceDelta.value, monthlyQuotaCny: quota.value, reason: adjustmentReason.value }); editingWallet.value = null; toastStore.success('用户余额与配额已更新'); await load(true) }
  catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '调度失败') }
}
async function addCredential() {
  if (credentialKey.value.length < 8) return
  try { await api.createCredential({ provider: credentialProvider.value, label: credentialLabel.value, apiKey: credentialKey.value }); credentialKey.value = ''; toastStore.success('密钥已加密保存并加入轮换'); await load(true) }
  catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '密钥保存失败') }
}
async function disableCredential(id: number) { await api.disableCredential(id); toastStore.info('密钥已停用'); await load(true) }

const money = (value: number | null | undefined) => `¥${Number(value || 0).toFixed(2)}`
const providerTone = (provider: ProviderBudgetView) => provider.progressPercent > 85 ? 'danger' : provider.progressPercent > 60 ? 'warn' : 'good'
function scrollToRecharge() { document.getElementById('recharge-panel')?.scrollIntoView({ behavior: 'smooth' }) }
</script>

<template>
  <div class="page-stack billing-page">
    <section class="page-intro page-intro--split"><div><p class="page-kicker">BALANCE & QUOTA</p><h2>余额、配额与充值</h2><p>用户可查看余额并充值；管理员可调度所有账户、供应商预算与加密 API 密钥。</p></div><span class="sandbox-pill"><i /> PAYMENT SANDBOX</span></section>
    <div v-if="loading" class="loading-grid"><span v-for="i in 4" :key="i" /></div>
    <div v-else-if="error && !summary" class="inline-alert inline-alert--error">{{ error }}</div>
    <template v-if="summary">
      <section class="wallet-hero panel"><div class="wallet-balance"><span class="wallet-icon"><AppIcon name="wallet" :size="25" /></span><div><p>可用余额</p><strong>{{ money(summary.wallet.balanceCny) }}</strong><small>当前登录用户</small></div></div><div class="wallet-quota"><div><span>本月调用配额</span><b>{{ money(summary.wallet.monthSpentCny) }} / {{ money(summary.wallet.monthlyQuotaCny) }}</b></div><div class="quota-track"><i :style="{ width: `${summary.wallet.quotaProgressPercent}%` }" /></div><p>仍可使用 <strong>{{ money(summary.wallet.remainingQuotaCny) }}</strong></p></div><button class="button button--dark" @click="scrollToRecharge">立即充值 <AppIcon name="arrow" :size="17" /></button></section>

      <section v-if="isAdmin" class="provider-budget-section"><header class="panel-header"><div><p class="page-kicker">ADMIN · PROVIDER BUDGET</p><h3>模型 API 额度</h3><p>官方余额仅在供应商提供余额接口且密钥可用时显示，否则明确标为本地预算。</p></div><span class="live-refresh"><i /> auto refresh · 15s</span></header><div class="provider-budget-grid"><article v-for="provider in providers" :key="provider.provider" class="panel provider-budget-card" :class="`is-${providerTone(provider)}`"><header><span class="provider-mark">{{ provider.provider === 'DEEPSEEK' ? 'DS' : provider.provider === 'KIMI' ? 'K' : 'Q' }}</span><div><strong>{{ provider.displayName }}</strong><small>{{ provider.configuredKeyCount }} keys · {{ provider.rotationMode }}</small></div></header><div class="provider-money"><span><small>本月预算</small><b>{{ money(provider.monthlyBudgetCny) }}</b></span><span><small>已使用</small><b>{{ money(provider.usedCny) }}</b></span><span><small>剩余</small><b>{{ money(provider.remainingCny) }}</b></span></div><div class="provider-progress"><i :style="{ width: `${provider.progressPercent}%` }" /></div><footer><span>已用 {{ provider.progressPercent.toFixed(1) }}%</span><span>{{ provider.providerReportedBalance == null ? '官方余额暂不可读' : `官方余额 ${money(provider.providerReportedBalance)}` }}</span></footer><div v-if="canManage" class="budget-edit"><input v-model.number="provider.monthlyBudgetCny" type="number" min="0" /><button @click="saveProvider(provider)">保存预算</button></div></article></div></section>

      <section id="recharge-panel" class="billing-grid"><article class="panel recharge-panel"><div class="panel-header"><div><p class="page-kicker">RECHARGE</p><h3>选择充值方式</h3><p>支付宝/微信通过二维码在手机确认；银行卡通过短信验证码校验。</p></div></div><div class="amount-presets"><button v-for="value in [20, 50, 100, 200, 500]" :key="value" :class="{ active: amount === value }" @click="amount = value">¥{{ value }}</button><label>自定义 ¥<input v-model.number="amount" type="number" min="1" max="10000" /></label></div><div class="payment-methods"><button :class="{ active: method === 'ALIPAY' }" @click="method = 'ALIPAY'"><b class="pay-logo alipay">支</b><span>支付宝<small>扫码支付</small></span></button><button :class="{ active: method === 'WECHAT' }" @click="method = 'WECHAT'"><b class="pay-logo wechat">微</b><span>微信支付<small>扫码支付</small></span></button><button :class="{ active: method === 'BANK_CARD' }" @click="method = 'BANK_CARD'"><b class="pay-logo bank"><AppIcon name="card" :size="19" /></b><span>银行卡<small>短信验证</small></span></button></div>
        <div v-if="method !== 'BANK_CARD'" class="sandbox-qr"><AppIcon name="qr" :size="28" /><div><strong>创建订单后显示可扫描二维码</strong><p>手机打开收银台确认后，本页每 2 秒自动检测到账。</p></div></div><div v-else class="bank-form"><label class="field-label">银行卡号<input v-model="cardNumber" class="field-input" inputmode="numeric" autocomplete="cc-number" maxlength="23" placeholder="6222 0000 0000 0000" @input="normalizeCard" /></label><div><label class="field-label">有效期<input v-model="expiry" class="field-input" maxlength="5" placeholder="MM/YY" /></label><label class="field-label">安全码<input v-model="cvv" class="field-input" type="password" maxlength="4" placeholder="CVV" /></label></div><label class="field-label">短信手机号<input v-model="phone" class="field-input" inputmode="tel" maxlength="11" placeholder="13800000000" /></label><p><AppIcon name="shield" :size="15" /> 完整卡号、有效期和 CVV 不发送至后端，仅手机号和卡号末四位用于沙箱验证。</p></div><div v-if="error" class="inline-alert inline-alert--error">{{ error }}</div><button class="button button--dark button--full" :disabled="busy" @click="createOrder">{{ busy ? '正在创建…' : `创建 ${money(amount)} 订单` }} <AppIcon name="arrow" :size="17" /></button></article>
        <aside class="panel verification-panel"><template v-if="pending?.method !== 'BANK_CARD' && pending?.qrPayload"><p class="page-kicker">SCAN TO PAY</p><h3>{{ pending.method === 'ALIPAY' ? '支付宝' : '微信' }}扫码支付</h3><QrCode :value="pending.qrPayload" /><p>{{ money(pending.amount) }} · 手机确认后自动到账</p><a :href="pending.qrPayload" target="_blank" rel="noreferrer">本机打开收银台 <AppIcon name="arrow" :size="15" /></a></template><template v-else-if="pending"><span class="verification-icon"><AppIcon name="phone" :size="24" /></span><p class="page-kicker">SMS VERIFY</p><h3>输入短信验证码</h3><p>已发送至 {{ pending.phoneMasked }} · 剩余 {{ pending.verificationAttemptsRemaining }} 次</p><div v-if="pending.sandboxVerificationCode" class="sandbox-code"><span>本地开发验证码</span><strong>{{ pending.sandboxVerificationCode }}</strong><small>生产模式不会显示</small></div><input v-model="verificationCode" class="otp-input" inputmode="numeric" maxlength="6" placeholder="000000" /><button class="button button--dark button--full" :disabled="busy || verificationCode.length !== 6 || pending.status === 'VERIFICATION_LOCKED'" @click="confirmOrder">检测验证码并入账</button></template><template v-else-if="successAmount"><span class="verification-icon success"><AppIcon name="check" :size="25" /></span><p class="page-kicker">PAYMENT SUCCESS</p><h3>充值成功</h3><strong class="success-money">+{{ money(successAmount) }}</strong></template><template v-else><span class="verification-icon"><AppIcon name="shield" :size="24" /></span><p class="page-kicker">SAFE BY DESIGN</p><h3>支付信息分层处理</h3><p>扫码令牌有时效且只存哈希；短信验证码最多错误 5 次；卡片敏感数据不落库。</p></template></aside></section>

      <section v-if="canManage" class="admin-billing-grid"><article class="panel"><header class="panel-header"><div><p class="page-kicker">ADMIN · ACCOUNTS</p><h3>用户余额与配额调度</h3></div></header><div class="admin-wallet-list"><button v-for="item in wallets" :key="item.userId" @click="editWallet(item)"><span>{{ item.displayName.slice(0, 1) }}</span><div><strong>{{ item.displayName }}</strong><small>@{{ item.username }} · {{ item.roleCode }}</small></div><b>{{ money(item.wallet.balanceCny) }}</b><em>配额 {{ money(item.wallet.monthlyQuotaCny) }}</em></button></div></article><article class="panel"><header class="panel-header"><div><p class="page-kicker">KEY ROTATION</p><h3>供应商 API 密钥</h3><p>AES-GCM 加密保存，只显示指纹。</p></div></header><div class="credential-form"><select v-model="credentialProvider" class="field-input"><option value="DEEPSEEK">DeepSeek</option><option value="KIMI">Kimi</option><option value="QWEN">通义千问</option></select><input v-model="credentialLabel" class="field-input" placeholder="标签" /><input v-model="credentialKey" class="field-input" type="password" autocomplete="off" placeholder="粘贴 API Key" /><button class="button button--dark" @click="addCredential">加密保存</button></div><div class="credential-list"><div v-for="item in credentials" :key="item.id"><span><b>{{ item.provider }} · {{ item.label }}</b><small>{{ item.fingerprint }} · {{ item.active ? '轮换中' : '已停用' }}</small></span><button v-if="item.active" @click="disableCredential(item.id)">停用</button></div></div></article></section>
    </template>

    <div v-if="editingWallet" class="modal-backdrop" @click.self="editingWallet = null"><form class="modal-card" @submit.prevent="saveWallet"><header><div><p class="page-kicker">ADMIN ADJUST</p><h3>调度 {{ editingWallet.displayName }}</h3></div><button type="button" class="icon-button" @click="editingWallet = null"><AppIcon name="close" /></button></header><label class="field-label">余额增减（可为负数）<input v-model.number="balanceDelta" class="field-input" type="number" step="0.01" /></label><label class="field-label">本月配额<input v-model.number="quota" class="field-input" type="number" min="0" step="0.01" /></label><label class="field-label">调度原因<input v-model="adjustmentReason" class="field-input" maxlength="180" required /></label><button class="button button--dark button--full">确认调整</button></form></div>
  </div>
</template>
