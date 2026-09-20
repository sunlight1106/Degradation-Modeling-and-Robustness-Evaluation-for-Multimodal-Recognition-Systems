<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import AppLogo from '@/components/AppLogo.vue'
import AppIcon from '@/components/AppIcon.vue'
import { api, ApiClientError } from '@/api/client'
import { toastStore } from '@/stores/toast'
import SyntheticScene from '@/components/SyntheticScene.vue'

const router = useRouter()
const username = ref('')
const email = ref('')
const password = ref('')
const visible = ref(false)
const busy = ref(false)
const error = ref('')

const passwordScore = computed(() => {
  const value = password.value
  let score = 0
  if (value.length >= 8) score++
  if (value.length >= 12) score++
  if (/[a-z]/.test(value) && /[A-Z]/.test(value)) score++
  if (/\d/.test(value)) score++
  if (/[^A-Za-z0-9]/.test(value)) score++
  return Math.min(3, Math.ceil(score / 1.6))
})

const strength = computed(() => {
  if (!password.value) return { label: '等待输入', tone: 'empty' }
  if (passwordScore.value <= 1) return { label: '弱', tone: 'weak' }
  if (passwordScore.value === 2) return { label: '中', tone: 'medium' }
  return { label: '强', tone: 'strong' }
})

async function submit() {
  error.value = ''
  if (passwordScore.value < 1) { error.value = '密码至少 8 位，并同时包含字母和数字'; return }
  busy.value = true
  try {
    await api.register({ username: username.value, email: email.value, password: password.value })
    toastStore.success('账号创建成功，请登录')
    await router.push({ name: 'login', query: { registered: '1', username: username.value } })
  } catch (reason) {
    error.value = reason instanceof ApiClientError ? reason.message : '注册失败，请稍后重试'
  } finally { busy.value = false }
}
</script>

<template>
  <div class="login-page register-page">
    <RouterLink to="/" class="login-brand"><AppLogo /></RouterLink>
    <section class="login-panel register-panel">
      <div class="login-copy">
        <span class="login-icon"><AppIcon name="users" :size="24" /></span>
        <h1>创建账号</h1>
        <p>三项信息即可开始。注册后默认获得实验与个人账单权限。</p>
      </div>
      <form class="login-form" @submit.prevent="submit">
        <label class="field-label">用户名
          <input v-model.trim="username" class="field-input" autocomplete="username" required minlength="3" maxlength="60" pattern="[A-Za-z0-9._-]+" placeholder="例如 liujiazhou" />
        </label>
        <label class="field-label">邮箱
          <input v-model.trim="email" class="field-input" type="email" autocomplete="email" required maxlength="160" placeholder="name@example.com" />
        </label>
        <label class="field-label">密码
          <span class="password-field">
            <input v-model="password" class="field-input" :type="visible ? 'text' : 'password'" autocomplete="new-password" required minlength="8" maxlength="72" placeholder="至少 8 位，包含字母和数字" />
            <button type="button" @click="visible = !visible">{{ visible ? '隐藏' : '显示' }}</button>
          </span>
        </label>
        <div class="password-strength" :class="`is-${strength.tone}`">
          <div><i v-for="index in 3" :key="index" :class="{ active: index <= passwordScore }" /></div>
          <span>密码强度 · <b>{{ strength.label }}</b></span>
        </div>
        <p class="password-guide">建议使用 12 位以上，并混合大小写、数字和符号。</p>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="button button--dark button--full" type="submit" :disabled="busy">
          {{ busy ? '正在创建…' : '创建 Personal Platform 账号' }} <AppIcon v-if="!busy" name="arrow" :size="17" />
        </button>
      </form>
      <p class="login-register-link">已有账号？<RouterLink to="/login">返回登录</RouterLink></p>
    </section>
    <div class="login-aside register-aside">
      <SyntheticScene class="login-photo" variant="receipt" scale="lg" scanning />
      <div class="login-quote"><span>“</span><p>一次可靠实验，从可追踪的账号、输入和模型版本开始。</p><small>Identity · Quota · Reproducibility</small></div>
      <div class="login-photo-shade" />
    </div>
  </div>
</template>
