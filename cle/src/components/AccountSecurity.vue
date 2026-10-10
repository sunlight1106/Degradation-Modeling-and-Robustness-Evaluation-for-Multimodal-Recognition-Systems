<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { authStore } from '@/stores/auth'
import { securityApi, type SecurityState } from '@/api/security'
const state=ref<SecurityState>(), password=ref(''), code=ref(''), secret=ref(''), recovery=ref<string[]>([]), busy=ref(false), message=ref(''), error=ref('')
let active=true; const owner=authStore.state.user?.id
function valid() { return active && owner===authStore.state.user?.id }
onBeforeUnmount(()=>{active=false; password.value=''; code.value=''; secret.value=''; recovery.value=[]})
watch(()=>authStore.state.user?.id,()=>{password.value=''; code.value=''; secret.value=''; recovery.value=[]; state.value=undefined})
onMounted(async()=>{try {const s=await securityApi.state(); if(valid()) state.value=s} catch(e){if(valid())error.value=(e as Error).message}})
async function run(action:'email'|'setup'|'enable'|'disable') {
 if(busy.value || !valid()) return; busy.value=true; error.value=''; message.value=''
 try {
  if(action==='email') {await securityApi.email(password.value); if(valid()) message.value='验证请求已提交，请查看邮箱。发送状态可刷新查看。'}
  if(action==='setup') {const s=await securityApi.setup(password.value); if(valid())secret.value=s.secret}
  if(action==='enable') {const codes=await securityApi.enable(password.value,code.value); if(valid()){recovery.value=codes; secret.value=''; message.value='验证已启用。保存下面的恢复码后重新登录。'}}
  if(action==='disable') {await securityApi.disable(password.value,code.value); if(valid()){message.value='验证已停用，请重新登录。'; authStore.clearSession()}}
 } catch(e){if(valid())error.value=(e as Error).message} finally {password.value=''; code.value=''; busy.value=false}
}
</script>
<template><article class="panel settings-card"><header><div><h3>邮箱与双重验证</h3><p>验证邮箱用于找回密码。验证器和一次性恢复码保护登录。</p></div></header>
 <p v-if="error" class="form-error" role="alert">{{error}}</p><p v-if="message" role="status">{{message}}</p>
 <template v-if="state&&!recovery.length"><p>邮箱：{{state.emailVerified?'已验证':'未验证'}} · 双重验证：{{state.mfaEnabled?'已启用':'未启用'}}</p><p v-if="!state.mailAvailable">邮件服务尚未配置，邮箱验证和密码找回暂不可用。</p><p v-if="state.delivery==='FAILED'" class="form-error">上次邮件发送失败，请让部署者检查邮件配置。</p>
 <label class="field-label">当前密码<input v-model="password" type="password" class="field-input" autocomplete="current-password" :disabled="busy" /></label>
 <div class="security-actions"><button class="button button--ghost" :disabled="busy||!password||!state.mailAvailable||state.emailVerified" @click="run('email')">发送验证邮件</button><button v-if="!state.mfaEnabled&&!secret" class="button button--dark" :disabled="busy||!password" @click="run('setup')">设置验证器</button></div>
 <div v-if="secret"><p>在验证器中手动添加下面的密钥，再填写六位验证码。此密钥十分钟内有效。</p><code>{{secret}}</code></div>
 <template v-if="secret||state.mfaEnabled"><label class="field-label">{{state.mfaEnabled?'验证码或恢复码':'六位验证码'}}<input v-model="code" class="field-input" autocomplete="one-time-code" maxlength="32" :disabled="busy" /></label><button class="button button--dark" :disabled="busy||!password||!code" @click="run(secret?'enable':'disable')">{{secret?'确认启用并退出所有登录':'停用并退出所有登录'}}</button><p v-if="state.mfaEnabled">剩余恢复码 {{state.recoveryCodesLeft}} 个</p></template></template>
 <div v-if="recovery.length"><p>恢复码只显示这一次，请保存到独立的安全位置。</p><pre>{{recovery.join('\n')}}</pre><a href="/login" class="button button--dark" @click="authStore.clearSession()">我已保存，重新登录</a></div>
 </article></template>
<style scoped>.security-actions{display:flex;gap:12px;margin:16px 0}code,pre{display:block;padding:16px;background:var(--surface-muted,#f3f5f7);overflow:auto}p{line-height:1.8}</style>
