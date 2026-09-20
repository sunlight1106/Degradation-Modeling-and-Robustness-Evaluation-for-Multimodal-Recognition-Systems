<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import AppLogo from '@/components/AppLogo.vue'
import AppIcon from '@/components/AppIcon.vue'
import { authStore } from '@/stores/auth'
import { ApiClientError } from '@/api/client'
import SyntheticScene from '@/components/SyntheticScene.vue'

const router = useRouter()
const route = useRoute()
const username = ref(typeof route.query.username === 'string' ? route.query.username : 'admin')
const password = ref('')
const error = ref('')
const visible = ref(false)

async function submit() {
  error.value = ''
  try {
    await authStore.login(username.value, password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    await router.push(redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/app/home')
  } catch (reason) {
    error.value = reason instanceof ApiClientError ? reason.message : '暂时无法登录，请检查后端服务'
  }
}
</script>

<template>
  <div class="login-page">
    <RouterLink to="/" class="login-brand"><AppLogo /></RouterLink>
    <section class="login-panel">
      <div class="login-copy">
        <span class="login-icon"><AppIcon name="spark" :size="24" /></span>
        <h1>欢迎回来</h1>
        <p>登录后继续管理数据、模型与鲁棒性实验。</p>
      </div>
      <form class="login-form" @submit.prevent="submit">
        <label class="field-label">用户名
          <input v-model="username" class="field-input" autocomplete="username" required maxlength="60" placeholder="请输入用户名" />
        </label>
        <label class="field-label">密码
          <span class="password-field">
            <input v-model="password" class="field-input" :type="visible ? 'text' : 'password'" autocomplete="current-password" required placeholder="请输入密码" />
            <button type="button" @click="visible = !visible">{{ visible ? '隐藏' : '显示' }}</button>
          </span>
        </label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="button button--dark button--full" type="submit" :disabled="authStore.state.loading">
          {{ authStore.state.loading ? '正在验证…' : '登录平台' }} <AppIcon v-if="!authStore.state.loading" name="arrow" :size="17" />
        </button>
      </form>
      <p class="login-register-link">还没有账号？<RouterLink to="/register">创建账号</RouterLink></p>
      <p class="login-footnote">初始账号由部署环境变量设置。首次登录后请修改默认密码。</p>
    </section>
    <div class="login-aside">
      <SyntheticScene class="login-photo" variant="plate" scale="lg" code="苏C88R21" scanning />
      <div class="login-quote">
        <span>“</span>
        <p>可靠的识别系统，不只是平均准确率更高；它还应知道何时不该自信。</p>
        <small>Robustness · Calibration · Selective output</small>
      </div>
      <div class="login-photo-shade" />
    </div>
  </div>
</template>
