<script setup lang="ts">
import PersonalAiDiagnostics from './PersonalAiDiagnostics.vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ApiClientError } from '@/api/client'
import { personalApi } from '@/api/personal'
import type { PersonalAiProvider, PersonalAiSetting } from '@/types/personal'
import { toastStore } from '@/stores/toast'

const providers = ref<PersonalAiProvider[]>([])
const settings = ref<PersonalAiSetting[]>([])
const provider = ref('')
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const form = reactive({ model: '', baseUrl: '', apiKey: '', enabled: true })
const selected = computed(() => providers.value.find(item => item.provider === provider.value))
const existing = computed(() => settings.value.find(item => item.provider === provider.value))
const configuredCount = computed(() => settings.value.filter(item => item.configured && item.enabled).length)
let active = true
function resetForm() {
  form.apiKey = ''
  form.model = existing.value?.model || ''
  form.baseUrl = existing.value?.baseUrl || selected.value?.baseUrls[0] || ''
  form.enabled = existing.value?.enabled ?? true
  error.value = ''
}
watch(provider, resetForm)
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [catalog, saved] = await Promise.all([personalApi.providers(), personalApi.settings()])
    if (!active) return
    providers.value = catalog; settings.value = saved
    if (!provider.value) provider.value = catalog[0]?.provider || ''
    resetForm()
  } catch (reason) { if (active) error.value = reason instanceof ApiClientError ? reason.message : '个人 AI 设置加载失败，请重试' }
  finally { if (active) loading.value = false }
}
async function save() {
  if (busy.value || !provider.value) return
  busy.value = true; error.value = ''
  const chosen = provider.value
  try {
    const saved = await personalApi.saveSetting(chosen, { model: form.model.trim(), baseUrl: form.baseUrl.trim() || undefined, apiKey: form.apiKey.trim() || undefined, enabled: form.enabled })
    if (!active) return
    settings.value = [...settings.value.filter(item => item.provider !== chosen), saved]
    resetForm()
    toastStore.success('个人 AI 配置已保存；尚未调用供应商')
  } catch (reason) { if (active) error.value = reason instanceof ApiClientError ? reason.message : '保存失败，请重试' }
  finally { form.apiKey = ''; if (active) busy.value = false }
}
async function remove() {
  if (busy.value || !existing.value) return
  if (!window.confirm(`删除 ${selected.value?.displayName || provider.value} 的个人密钥和配置？后续将无法使用此配置调用模型。`)) return
  busy.value = true; error.value = ''
  const chosen = provider.value
  try {
    await personalApi.deleteSetting(chosen)
    if (!active) return
    settings.value = settings.value.filter(item => item.provider !== chosen)
    resetForm(); toastStore.success('个人密钥和配置已删除')
  } catch (reason) { if (active) error.value = reason instanceof ApiClientError ? reason.message : '删除失败' }
  finally { form.apiKey = ''; if (active) busy.value = false }
}
onMounted(load)
onBeforeUnmount(() => { active = false; form.apiKey = '' })
</script>
<template>
  <article class="panel settings-card personal-ai-settings">
    <header><div><h3>个人 AI 服务</h3><p>使用你自己的 API 密钥。每个账户独立保存配置，费用由供应商向你的 API 账户结算。</p></div><span class="settings-badge">{{ configuredCount }} 个已启用</span></header>
    <p class="settings-notice">密钥仅用于你的请求，不会显示给其他用户。每次在笔记中调用模型前，你都可以审阅将发送的完整内容。平台余额与个人 AI 费用分开。</p>
    <p v-if="error" class="inline-alert inline-alert--error" role="alert">{{ error }} <button v-if="!providers.length" class="table-action" @click="load">重试</button></p>
    <p v-if="loading" class="settings-empty" role="status">正在加载供应商…</p>
    <form v-else-if="providers.length" class="settings-form" autocomplete="off" @submit.prevent="save">
      <div class="settings-fields">
        <label class="field-label">供应商<select v-model="provider" class="field-input" :disabled="busy"><option v-for="item in providers" :key="item.provider" :value="item.provider">{{ item.displayName }}</option></select></label>
        <label class="field-label">模型 ID<input v-model="form.model" class="field-input" maxlength="120" placeholder="填写此供应商账户可用的模型 ID" required :disabled="busy" /></label>
      </div>
      <p v-if="selected && !selected.remoteEnabled" class="inline-alert">此部署尚未开启外部模型调用。可以保存配置和预览内容；实际调用需部署管理员启用。</p>
      <label class="field-label">API 端点
        <select v-if="selected?.baseUrls.length && !selected.customEndpointAllowed" v-model="form.baseUrl" class="field-input" :disabled="busy"><option v-for="url in selected.baseUrls" :key="url" :value="url">{{ url }}</option></select>
        <input v-else v-model="form.baseUrl" class="field-input" type="url" maxlength="300" placeholder="https://…（必须在部署端允许列表中）" :disabled="busy" required />
        <small>仅支持后端允许的 HTTPS 端点。模型名称与账户可用性以供应商为准；保存配置不会验证额度或发起模型调用。</small>
      </label>
      <label class="field-label">{{ existing?.configured ? '更换 API 密钥（留空保留现有密钥）' : 'API 密钥' }}<input v-model="form.apiKey" type="password" class="field-input" autocomplete="new-password" name="personal-ai-key" maxlength="1000" :required="!existing?.configured" :disabled="busy" :placeholder="existing?.configured ? '已配置 · 不回显密钥' : '输入你自己的 API 密钥'" /></label>
      <label class="settings-check"><input v-model="form.enabled" type="checkbox" :disabled="busy" /> 启用此个人配置</label>
      <p class="field-hint">输入的密钥不会写入浏览器存储。保存尝试结束、切换供应商或关闭此页面后，输入框都会清空。</p>
      <div class="settings-button-row"><button class="button button--dark" :disabled="busy">{{ busy ? '处理中…' : '保存个人配置' }}</button><button type="button" class="button button--ghost" :disabled="busy" @click="resetForm">取消更改</button><button v-if="existing" type="button" class="button button--ghost note-danger" :disabled="busy" @click="remove">删除密钥与配置</button></div>
    </form>
    <PersonalAiDiagnostics v-if="existing" :key="existing.provider" :setting="existing" :disabled="busy" /><div v-if="settings.length" class="settings-saved-list"><h4>我的已保存配置</h4><div v-for="item in settings" :key="item.provider"><strong>{{ providers.find(p => p.provider === item.provider)?.displayName || item.provider }}</strong><span>{{ item.model }}</span><span>{{ item.configured ? item.enabled ? '已启用' : '已停用' : '未配置密钥' }}</span></div></div>
  </article>
</template>
