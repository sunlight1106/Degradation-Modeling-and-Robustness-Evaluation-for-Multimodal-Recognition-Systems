<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import AppLogo from '@/components/AppLogo.vue'
import AppIcon from '@/components/AppIcon.vue'
import { api, ApiClientError } from '@/api/client'
import { toastStore } from '@/stores/toast'


const router = useRouter()
const username = ref('')
const email = ref('')
const password = ref('')
const confirmation = ref(''), displayName = ref(''), discoverable = ref(true)
let active = true
onBeforeUnmount(()=>{active=false;password.value='';confirmation.value=''})
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
  if (busy.value) return
  if (password.value.length<8 || new TextEncoder().encode(password.value).length>72 || !/[A-Za-z]/.test(password.value) || !/\d/.test(password.value)) { error.value = '密码至少 8 位，同时包含字母和数字，UTF-8 编码不超过 72 字节'; return }
  if (password.value!==confirmation.value) { error.value='两次密码不一致'; return }
  busy.value = true
  try {
    await api.register({ username: username.value, email: email.value, password: password.value, displayName:displayName.value, discoverable:discoverable.value })
    if (!active) return
    toastStore.success('账号创建成功，请登录')
    await router.push({ name: 'login', query: { registered: '1', username: username.value } })
  } catch (reason) {
    if (!active) return
    error.value = reason instanceof ApiClientError ? reason.message : '注册失败，请稍后重试'
  } finally { busy.value = false; password.value=''; confirmation.value='' }
}
</script>

<template>
  <div class="login-page register-page">
    <RouterLink to="/" class="login-brand"><AppLogo /></RouterLink>
    <section class="login-panel register-panel">
      <div class="login-copy">
        <span class="login-icon"><AppIcon name="users" :size="24" /></span>
        <h1>创建账号</h1>
        <p>账号保存在当前平台服务器，可在其他设备登录同一网站继续使用。</p>
      </div>
      <form class="login-form" @submit.prevent="submit">
        <label class="field-label">用户名
          <input v-model.trim="username" class="field-input" autocomplete="username" required minlength="3" maxlength="60" pattern="[A-Za-z0-9._-]+" placeholder="例如 liujiazhou" />
        </label>
        <label class="field-label">邮箱
          <input v-model.trim="email" class="field-input" type="email" autocomplete="email" required maxlength="160" placeholder="name@example.com" />
        </label>
        <label class="field-label">显示名称（可重复）<input v-model.trim="displayName" class="field-input" maxlength="80" placeholder="留空则使用用户名" :disabled="busy" /></label>
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
        <label class="field-label">确认密码<input v-model="confirmation" class="field-input" type="password" autocomplete="new-password" required maxlength="72" :disabled="busy" /></label>
        <label class="account-remember"><input v-model="discoverable" type="checkbox" :disabled="busy" />允许其他用户搜索到我</label>
        <p class="password-guide">建议使用 12 位以上，并混合大小写、数字和符号。注册后，其他登录用户可以按用户名或昵称搜索你；可在「联系人与聊天」关闭。邮箱和私密笔记不会公开。</p>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="button button--dark button--full" type="submit" :disabled="busy">
          {{ busy ? '正在创建…' : '创建账号' }} <AppIcon v-if="!busy" name="arrow" :size="17" />
        </button>
      </form>
      <p class="login-register-link">已有账号？<RouterLink to="/login">返回登录</RouterLink></p>
    </section>
    <aside class="login-aside research-login-note"><p class="research-eyebrow">KNOWLEDGE / AI EVALUATION</p><h2>建立自己的<br>研究档案。</h2><p>用一个账号连接素材、模型和笔记，<br>让实验过程有据可查。</p><ol><li>01 / 整理素材</li><li>02 / 记录实验</li><li>03 / 回顾与复现</li></ol><RouterLink to="/docs">阅读使用文档 →</RouterLink></aside>
  </div>
</template>
