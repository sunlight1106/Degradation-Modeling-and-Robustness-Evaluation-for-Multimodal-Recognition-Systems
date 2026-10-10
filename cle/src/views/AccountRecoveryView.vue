<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { securityApi } from '@/api/security'
const route=useRoute(), verify=route.path==='/verify-email', reset=route.path==='/reset-password'
const token=new URLSearchParams(location.hash.slice(1)).get('token')||''
if(token) history.replaceState(history.state,'',location.pathname+location.search)
const email=ref(''),password=ref(''),confirm=ref(''),busy=ref(false),done=ref(false),error=ref('')
async function submit(){if(busy.value)return;error.value='';if(reset&&password.value!==confirm.value){error.value='两次密码不一致';return}busy.value=true;try{if(verify)await securityApi.verify(token);else if(reset)await securityApi.reset(token,password.value);else await securityApi.forgot(email.value);done.value=true;password.value='';confirm.value=''}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
</script>
<template><main class="recovery-page"><RouterLink to="/login">← 返回登录</RouterLink><h1>{{verify?'验证邮箱':reset?'重设密码':'找回密码'}}</h1><p v-if="done" role="status">{{verify?'邮箱已验证。':reset?'密码已更新，旧登录已退出，请重新登录。':'如果该邮箱已验证且账号可用，我们会发送重设链接。请查看邮箱。'}}</p><form v-else @submit.prevent="submit"><p v-if="error" class="form-error" role="alert">{{error}}</p><p v-if="verify">点击确认完成邮箱验证。</p><template v-else-if="reset"><label class="field-label">新密码<input v-model="password" class="field-input" type="password" autocomplete="new-password" minlength="8" maxlength="72" required /></label><label class="field-label">再次输入<input v-model="confirm" class="field-input" type="password" autocomplete="new-password" required /></label></template><label v-else class="field-label">已验证的邮箱<input v-model="email" class="field-input" type="email" autocomplete="email" required maxlength="160" /></label><button class="button button--dark" :disabled="busy||((verify||reset)&&!token)">{{busy?'处理中…':verify?'确认验证':reset?'更新密码':'发送重设链接'}}</button><p v-if="(verify||reset)&&!token">链接不完整，请从收到的邮件重新打开。</p></form></main></template>
<style scoped>.recovery-page{max-width:520px;margin:12vh auto;padding:32px}.recovery-page h1{margin:28px 0}.field-label{margin:20px 0}.recovery-page p{line-height:1.8}</style>
