<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import AppLogo from '@/components/AppLogo.vue'
import AppIcon from '@/components/AppIcon.vue'
import RememberedAccounts from '@/components/RememberedAccounts.vue'
import { rememberedAccounts } from '@/stores/rememberedAccounts'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'
import { ApiClientError } from '@/api/client'

const router = useRouter()
const route = useRoute()
const recovering = computed(() => !authStore.state.user && route.query.mode === 'reactivate')
const username = ref(typeof route.query.username === 'string' ? route.query.username : '')
const password = ref('')
const error = ref('')
const visible = ref(false), remember = ref(false)
const otp = ref('')
const passwordInput = ref<HTMLInputElement | null>(null)
async function choose(name: string) { username.value = name; password.value = ''; error.value = ''; await nextTick(); passwordInput.value?.focus() }
watch(() => authStore.state.user?.id, () => { password.value = '' }, { flush: 'sync' })
onBeforeUnmount(() => { password.value = ''; otp.value = '' })

async function submit() {
  if (authStore.state.loading) return
  error.value = ''
  try {
    const user = authStore.state.user ? await authStore.switchAccount(username.value.trim(), password.value, otp.value) : await authStore.login(username.value.trim(), password.value, otp.value, recovering.value)
    if (remember.value && !rememberedAccounts.remember(user)) toastStore.info('登录成功；浏览器没有允许保存账号名称。')
    password.value = ''
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    await router.push(redirect.startsWith('/app/') && !redirect.includes('\\') || redirect.startsWith('/shared/') && !redirect.includes('\\') ? redirect : '/app/home')
  } catch (reason) {
    password.value = ''
    error.value = reason instanceof ApiClientError ? reason.message : '暂时无法登录，请检查后端服务'
  } finally { otp.value = ''
  }
}
</script>

<template>
  <div class="login-page">
    <RouterLink to="/" class="login-brand"><AppLogo /></RouterLink>
    <section class="login-panel">
      <div class="login-copy">
        <span class="login-icon"><AppIcon name="spark" :size="24" /></span>
        <h1>{{recovering?'恢复停用的账号':'欢迎回来'}}</h1>
        <p>{{recovering?'仅适用于自己暂时停用的账号，永久注销或管理员禁用的账号不能自助恢复。':'登录后继续管理知识、模型与识别实验。'}}</p>
      </div>
      <p v-if="route.query.loggedOut === '1'" class="login-footnote" role="status">当前会话已退出，你可以选择其他账号登录。</p>
      <p v-if="authStore.state.user" class="login-footnote">当前已登录 @{{ authStore.state.user.username }}。<RouterLink to="/account/switch">切换其他账号 →</RouterLink></p>
      <RememberedAccounts :current="authStore.state.user?.username" :disabled="authStore.state.loading" @choose="choose" />
      <form class="login-form" @submit.prevent="submit">
        <label class="field-label">用户名、身份码或已验证邮箱
          <input v-model="username" class="field-input" autocomplete="username" required maxlength="160" :disabled="authStore.state.loading" placeholder="用户名 / PKB-… / 邮箱" />
        </label>
        <label class="field-label">密码
          <span class="password-field">
            <input ref="passwordInput" v-model="password" class="field-input" :type="visible ? 'text' : 'password'" autocomplete="current-password" required :disabled="authStore.state.loading" placeholder="请输入密码" />
            <button type="button" @click="visible = !visible">{{ visible ? '隐藏' : '显示' }}</button>
          </span>
        </label>
        <label class="field-label">验证码或恢复码（启用双重验证时填写）<input v-model="otp" class="field-input" autocomplete="one-time-code" maxlength="32" /></label><label class="account-remember"><input v-model="remember" type="checkbox" :disabled="authStore.state.loading" />在此浏览器记住这个账号名称</label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="button button--dark button--full" type="submit" :disabled="authStore.state.loading">
          {{ authStore.state.loading ? '正在验证…' : recovering?'恢复并登录':'登录平台' }} <AppIcon v-if="!authStore.state.loading" name="arrow" :size="17" />
        </button>
      </form>
      <p v-if="!authStore.state.user" class="login-register-link"><RouterLink :to="recovering?'/login':'/login?mode=reactivate'">{{recovering?'返回普通登录':'恢复暂时停用的账号'}}</RouterLink></p>
      <p class="login-register-link"><RouterLink to="/forgot-password">忘记密码？</RouterLink></p><p class="login-register-link">还没有账号？<RouterLink to="/register">创建账号</RouterLink></p>
      <p class="login-footnote">初始账号由部署环境变量设置。首次登录后请修改默认密码。</p>
    </section>
    <aside class="login-aside research-login-note"><p class="research-eyebrow">KNOWLEDGE / AI EVALUATION</p><h2>让每次探索，<br>都有清晰的记录。</h2><p>连接模型，整理知识，对照实验结果。<br>从这里继续你的研究。</p><ol><li>01 / 准备输入</li><li>02 / 运行实验</li><li>03 / 记录发现</li></ol><RouterLink to="/docs">查看使用文档 →</RouterLink></aside>
  </div>
</template>
