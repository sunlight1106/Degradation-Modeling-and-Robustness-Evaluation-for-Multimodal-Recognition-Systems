<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import AppLogo from '@/components/AppLogo.vue'
import AppIcon from '@/components/AppIcon.vue'
import RememberedAccounts from '@/components/RememberedAccounts.vue'
import { authStore } from '@/stores/auth'
import { ApiClientError } from '@/api/client'
import { rememberedAccounts } from '@/stores/rememberedAccounts'
import { toastStore } from '@/stores/toast'

const route = useRoute(), router = useRouter()
const exiting = computed(() => route.name === 'account-logout')
const username = ref(''), password = ref(''), visible = ref(false), remember = ref(false), busy = ref(false), error = ref('')
const otp = ref('')
const passwordInput = ref<HTMLInputElement | null>(null)
let active = true, completed = false
const currentRemembered = computed(() => rememberedAccounts.state.items.some(item => item.username === authStore.state.user?.username))
function rememberCurrent() {
  const user = authStore.state.user
  if (user && !rememberedAccounts.remember(user)) toastStore.error('浏览器没有允许保存账号名称。')
}
watch(() => authStore.state.user?.id, next => { password.value = ''; if (!next && !busy.value && active) void router.replace('/login') }, { flush: 'sync' })
async function choose(name: string) { username.value = name; password.value = ''; error.value = ''; await nextTick(); passwordInput.value?.focus() }
async function submit() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const user = await authStore.switchAccount(username.value.trim(), password.value, otp.value)
    if (!active) return
    if (remember.value && !rememberedAccounts.remember(user)) toastStore.info('已切换账号；浏览器没有允许保存账号名称。')
    completed = true; password.value = ''
    await router.replace('/app/home')
  } catch (reason) {
    if (!active) return
    error.value = reason instanceof ApiClientError ? reason.message : '网络异常，切换结果暂未确认。请重新登录确认账号。'
    if (!authStore.state.user) { completed = true; await router.replace('/login') }
  } finally { password.value = ''; if (active) busy.value = false }
}
async function logout() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await authStore.logout(); if (active) { completed = true; await router.replace('/login?loggedOut=1') } }
  catch (reason) {
    if (!active) return
    if (!authStore.state.user) { completed = true; await router.replace('/login'); return }
    error.value = reason instanceof ApiClientError ? reason.message : '暂时无法确认退出，当前会话仍保留。请重试。'
  } finally { if (active) busy.value = false }
}
onBeforeRouteLeave(() => !busy.value || completed)
onMounted(() => { if (exiting.value) void logout() })
onBeforeUnmount(() => { active = false; password.value = '' })
</script>

<template>
  <div class="login-page account-access-page">
    <RouterLink to="/" class="login-brand"><AppLogo /></RouterLink>
    <section class="login-panel account-access-panel">
      <div class="login-copy"><span class="login-icon"><AppIcon :name="exiting ? 'logout' : 'users'" :size="24" /></span><h1>{{ exiting ? '退出当前登录' : '切换账号' }}</h1><p>{{ exiting ? '正在结束这个浏览器的登录会话。' : '使用另一个账号，继续你的学习与研究。' }}</p></div>
      <div v-if="authStore.state.user" class="account-current"><span class="account-monogram">{{ authStore.state.user.displayName.slice(0, 1) }}</span><div><small>当前登录</small><strong>{{ authStore.state.user.displayName }}</strong><span>@{{ authStore.state.user.username }} · {{ authStore.state.user.roleName }}</span></div><button v-if="!exiting && !currentRemembered" type="button" class="account-remember-current" :disabled="busy" @click="rememberCurrent">记住名称</button></div>
      <template v-if="!exiting">
        <RememberedAccounts :current="authStore.state.user?.username" :disabled="busy" @choose="choose" />
        <form class="login-form" @submit.prevent="submit">
          <label class="field-label">另一个账号的用户名<input v-model="username" class="field-input" autocomplete="username" required maxlength="60" :disabled="busy" placeholder="选择上方账号，或直接输入用户名" /></label>
          <label class="field-label">该账号的密码<span class="password-field"><input ref="passwordInput" v-model="password" class="field-input" :type="visible ? 'text' : 'password'" autocomplete="current-password" required :disabled="busy" /><button type="button" :disabled="busy" @click="visible = !visible">{{ visible ? '隐藏' : '显示' }}</button></span></label>
          <label class="field-label">验证码或恢复码（启用双重验证时填写）<input v-model="otp" class="field-input" autocomplete="one-time-code" maxlength="32" /></label><label class="account-remember"><input v-model="remember" type="checkbox" :disabled="busy" />在此浏览器记住这个账号名称</label>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <button class="button button--dark button--full" :disabled="busy || !username.trim() || !password || username.trim().toLowerCase() === authStore.state.user?.username.toLowerCase()">{{ busy ? '正在验证并切换…' : '验证并切换账号' }}</button>
        </form>
        <p class="login-footnote">验证成功后结束当前会话。笔记、联系人、个人 AI 和权限将随账号切换；其他设备的独立登录保留。</p>
      </template>
      <template v-else><p v-if="error" class="form-error" role="alert">{{ error }}</p><button v-if="error" class="button button--dark button--full" :disabled="busy" @click="logout">重试退出</button><p v-else role="status">正在安全退出…</p></template>
      <RouterLink v-if="!busy" class="account-return" to="/app/settings">返回当前账号</RouterLink>
    </section>
    <aside class="login-aside research-login-note"><p class="research-eyebrow">YOUR ACCOUNT</p><h2>同一平台，<br />独立的个人空间。</h2><p>每个账号有自己的笔记、消息和设置。<br />切换后，只能访问新账号有权限的内容。</p><RouterLink to="/app/settings?section=security">管理登录设备 →</RouterLink></aside>
  </div>
</template>
