<script setup lang="ts">
import { rememberedAccounts } from '@/stores/rememberedAccounts'
import { toastStore } from '@/stores/toast'
defineProps<{ current?: string; disabled?: boolean }>()
defineEmits<{ choose: [username: string] }>()
function remove(username?: string) {
  const ok = username ? rememberedAccounts.forget(username) : rememberedAccounts.clear()
  if (!ok) toastStore.error('浏览器未允许修改账号记录，请检查网站存储设置。')
}
</script>
<template>
  <section v-if="rememberedAccounts.state.items.length" class="remembered-accounts" aria-label="记住的账号">
    <header><span>此浏览器记住的账号</span><button type="button" :disabled="disabled" @click="remove()">清空记录</button></header>
    <div v-for="account in rememberedAccounts.state.items" :key="account.username" class="remembered-account">
      <button type="button" class="remembered-account-pick" :disabled="disabled || account.username.toLowerCase() === current?.toLowerCase()" @click="$emit('choose', account.username)"><span class="account-monogram">{{ (account.displayName || account.username).slice(0, 1) }}</span><span><strong>{{ account.displayName || account.username }}</strong><small>@{{ account.username }}</small></span><small v-if="account.username.toLowerCase() === current?.toLowerCase()">当前账号</small><span v-else aria-hidden="true">↗</span></button>
      <button type="button" class="remembered-account-remove" :aria-label="`移除 ${account.username} 的名称记录`" :disabled="disabled" @click="remove(account.username)">移除</button>
    </div>
    <p>仅保存账号名称，选择后仍需输入密码。移除记录不会删除账号。</p>
  </section>
</template>
