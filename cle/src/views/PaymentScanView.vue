<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type { PublicPaymentView } from '@/types/api'
import AppLogo from '@/components/AppLogo.vue'
import AppIcon from '@/components/AppIcon.vue'

const route = useRoute()
const payment = ref<PublicPaymentView | null>(null)
const loading = ref(true)
const paying = ref(false)
const error = ref('')
const token = computed(() => String(route.params.token || ''))
const brand = computed(() => payment.value?.method === 'WECHAT' ? '微信支付' : '支付宝')

async function load() {
  try { payment.value = await api.publicPayment(token.value) }
  catch (reason) { error.value = reason instanceof ApiClientError ? reason.message : '支付订单无法读取' }
  finally { loading.value = false }
}

async function complete() {
  paying.value = true
  error.value = ''
  try { payment.value = await api.completePublicPayment(token.value) }
  catch (reason) { error.value = reason instanceof ApiClientError ? reason.message : '支付确认失败' }
  finally { paying.value = false }
}

onMounted(load)
</script>

<template>
  <main class="pay-page">
    <header><RouterLink to="/"><AppLogo /></RouterLink><span>安全沙箱收银台</span></header>
    <section class="pay-card">
      <div v-if="loading" class="large-skeleton" />
      <template v-else-if="payment">
        <span class="pay-brand" :class="payment.method.toLowerCase()">{{ payment.method === 'WECHAT' ? '微' : '支' }}</span>
        <p class="page-kicker">{{ brand.toUpperCase() }} · SANDBOX</p>
        <h1>{{ payment.status === 'PAID' ? '支付已完成' : `向 ${payment.merchantName} 付款` }}</h1>
        <strong class="pay-amount">¥{{ Number(payment.amount).toFixed(2) }}</strong>
        <p v-if="payment.status === 'PENDING_PAYMENT'">这是本地演示收银台。确认后，原设备会自动检测到账状态。</p>
        <div v-else class="payment-success"><AppIcon name="check" :size="24" /> 订单已安全入账，可以关闭此页面。</div>
        <button v-if="payment.status === 'PENDING_PAYMENT'" class="button button--dark button--full" :disabled="paying" @click="complete">
          {{ paying ? '正在确认…' : `确认使用${brand}支付` }}
        </button>
        <small>订单 {{ payment.orderId.slice(0, 8) }} · 有效期至 {{ new Date(payment.expiresAt).toLocaleTimeString('zh-CN') }}</small>
      </template>
      <div v-if="error" class="inline-alert inline-alert--error">{{ error }}</div>
    </section>
  </main>
</template>
