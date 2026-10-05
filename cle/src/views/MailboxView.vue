<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type { MessageView, MessageContactView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'

const route = useRoute()
const tab = ref<'inbox' | 'sent'>('inbox')
const messages = ref<MessageView[]>([]), directory = ref<MessageContactView[]>([])
const selected = ref<MessageView | null>(null), composing = ref(false)
const recipients = ref<number[]>([]), subject = ref(''), body = ref(''), query = ref('')
const files = ref<File[]>([]), replyToId = ref<string | undefined>()
const busy = ref(false), loading = ref(true), error = ref('')
let epoch = 0, detailEpoch = 0
const unread = computed(() => tab.value === 'inbox' ? messages.value.filter(item => !item.read).length : 0)
const contacts = computed(() => directory.value.filter(user => `${user.displayName} ${user.username}`.toLowerCase().includes(query.value.toLowerCase())))
const relation = { ADMIN: '平台管理员', GROUP_MEMBER: '联系人或同组成员', USER: '平台用户' }
const reasonText = (reason: unknown) => reason instanceof ApiClientError ? reason.message : '操作失败，请重试'
function clearDraft() { recipients.value = []; subject.value = ''; body.value = ''; files.value = []; replyToId.value = undefined; query.value = '' }
function reset() { epoch++; detailEpoch++; messages.value = []; directory.value = []; selected.value = null; composing.value = false; clearDraft(); error.value = ''; busy.value = false; loading.value = false }
async function load() {
  const version = ++epoch
  loading.value = true; error.value = ''
  try {
    const [items, users] = await Promise.all([tab.value === 'inbox' ? api.inbox() : api.sent(), api.messageDirectory()])
    if (version !== epoch) return
    messages.value = items; directory.value = users
  } catch (reason) { if (version === epoch) { error.value = reasonText(reason); messages.value = []; directory.value = [] } }
  finally { if (version === epoch) loading.value = false }
}
async function switchTab(next: 'inbox' | 'sent') { if (busy.value) return; detailEpoch++; tab.value = next; selected.value = null; await load() }
async function open(item: MessageView) {
  const version = epoch, selection = ++detailEpoch
  selected.value = null
  try { const message = await api.message(item.id); if (version === epoch && selection === detailEpoch) { selected.value = message; item.read = true } }
  catch (reason) { if (version === epoch && selection === detailEpoch) error.value = reasonText(reason) }
}
function compose(to?: number, original?: MessageView) {
  if (busy.value || loading.value) return
  if (to && !directory.value.some(user => user.id === to)) { error.value = '此用户已不可联系，请刷新后选择平台管理员或联系人或同组成员。'; return }
  clearDraft(); if (to) recipients.value = [to]
  if (original) { subject.value = (original.subject.startsWith('回复：') ? original.subject : `回复：${original.subject}`).slice(0, 180); replyToId.value = original.id }
  composing.value = true; error.value = ''
}
function chooseFiles(event: Event) {
  const input = event.target as HTMLInputElement, chosen = Array.from(input.files || [])
  if (chosen.length > 5 || chosen.some(file => file.size > 20 * 1024 * 1024)) { error.value = '最多 5 个附件，每个不超过 20 MB。'; files.value = []; input.value = ''; return }
  files.value = chosen; error.value = ''
}
async function send() {
  if (busy.value || !recipients.value.length || recipients.value.length > 20 || !subject.value.trim() || !body.value.trim()) return
  const version = epoch
  busy.value = true; error.value = ''
  try {
    await api.sendMessage({ recipientIds: recipients.value, subject: subject.value, body: body.value, files: files.value, replyToId: replyToId.value })
    if (version !== epoch) return
    composing.value = false; clearDraft(); busy.value = false
    toastStore.success('站内信已发送'); await switchTab('sent')
  } catch (reason) { if (version === epoch) error.value = reasonText(reason) }
  finally { if (version === epoch) busy.value = false }
}
async function download(file: MessageView['attachments'][number]) {
  const version = epoch
  try { await api.download(file.downloadUrl, file.fileName) }
  catch (reason) { if (version === epoch) error.value = reasonText(reason) }
}
watch(() => authStore.state.user?.id, () => { reset(); if (authStore.state.user) void load() }, { flush: 'sync' })
onMounted(async () => { const version = epoch + 1; await load(); if (epoch === version && route.query.to) compose(Number(route.query.to)) })
onBeforeUnmount(reset)
</script>

<template>
  <div class="page-stack mailbox-page">
    <section class="page-intro page-intro--split"><div><p class="page-kicker">COLLABORATION</p><h2>站内信箱</h2><p>联系平台管理员或联系人或同组成员；群内讨论和资料分享请进入群组协作。</p></div><button class="button button--dark" :disabled="loading || busy" @click="compose()"><AppIcon name="plus" :size="17" /> 写信</button></section>
    <div class="mail-shortcuts"><RouterLink to="/app/groups">打开群组协作 →</RouterLink><button type="button" :disabled="loading || busy" @click="load">刷新联系人与消息</button><button v-for="admin in directory.filter(user => user.relationship === 'ADMIN')" :key="admin.id" type="button" :disabled="loading || busy" @click="compose(admin.id)">联系管理员 · {{ admin.displayName }}</button></div>
    <section class="mail-layout panel">
      <aside class="mail-list">
        <header><div><button :class="{ active: tab === 'inbox' }" @click="switchTab('inbox')">收件箱 <b>{{ unread }}</b></button><button :class="{ active: tab === 'sent' }" @click="switchTab('sent')">已发送</button></div></header>
        <div v-if="loading" class="table-skeleton" />
        <EmptyState v-else-if="!messages.length" title="暂无消息" description="新消息会出现在这里。" icon="mail" />
        <button v-for="item in messages" v-else :key="item.id" class="mail-row" :class="{ active: selected?.id === item.id, unread: !item.read }" @click="open(item)">
          <span>{{ item.senderName.slice(0, 1) }}</span><div><strong>{{ item.subject }}</strong><small>{{ tab === 'inbox' ? item.senderName : `发送给 ${item.recipients.map(r => r.displayName).join('、')}` }}</small></div><time>{{ new Date(item.createdAt).toLocaleDateString('zh-CN') }}</time>
        </button>
        <p v-if="error" class="inline-alert inline-alert--error">{{ error }}</p>
      </aside>
      <article class="mail-reader">
        <template v-if="selected"><p class="page-kicker">{{ tab === 'inbox' ? 'INBOX' : 'SENT' }}</p><h3>{{ selected.subject }}</h3><div class="mail-meta"><b>{{ selected.senderName }}</b><span>发送给 {{ selected.recipients.map(r => r.displayName).join('、') }}</span><time>{{ new Date(selected.createdAt).toLocaleString('zh-CN') }}</time></div><button v-if="tab === 'inbox' && selected.senderId !== authStore.state.user?.id" class="button button--ghost" :disabled="busy || loading" @click="compose(selected.senderId, selected)">回复这封信</button><p class="mail-body">{{ selected.body }}</p><div v-if="selected.attachments.length" class="mail-attachments"><button v-for="file in selected.attachments" :key="file.id" @click="download(file)"><AppIcon name="file" :size="18" /><span>{{ file.fileName }}<small>{{ Math.ceil(file.sizeBytes / 1024) }} KB</small></span><AppIcon name="download" :size="16" /></button></div></template>
        <EmptyState v-else title="选择一封消息" description="邮件内容和附件会显示在这里。" icon="mail" />
      </article>
    </section>

    <div v-if="composing" class="modal-backdrop" @click.self="!busy && (composing = false)">
      <form class="modal-card compose-modal" role="dialog" aria-modal="true" aria-label="写站内信" @submit.prevent="send">
        <header><h3>{{ replyToId ? '回复站内信' : '写站内信' }}</h3><button type="button" class="icon-button" aria-label="关闭写信" :disabled="busy" @click="composing = false"><AppIcon name="close" /></button></header>
        <p>可联系平台管理员和有交流权限的联系人或同组成员，最多 20 人。</p>
        <label class="field-label">查找收件人<input v-model="query" class="field-input" type="search" placeholder="姓名或用户名" :disabled="busy" /></label>
        <fieldset class="mail-contacts"><legend>收件人 · 已选 {{ recipients.length }} 人</legend><label v-for="user in contacts" :key="user.id"><input v-model="recipients" type="checkbox" :value="user.id" :disabled="busy || (recipients.length >= 20 && !recipients.includes(user.id)) || (!!replyToId && !recipients.includes(user.id))" /><span>{{ user.displayName }} <small>@{{ user.username }} · {{ relation[user.relationship] }}</small></span></label><p v-if="!contacts.length">没有匹配的联系人，请检查群组成员或联系平台管理员。</p></fieldset>
        <label class="field-label">主题<input v-model="subject" class="field-input" maxlength="180" required :disabled="busy" /></label><label class="field-label">正文<textarea v-model="body" class="field-input" rows="7" maxlength="20000" required :disabled="busy" /></label>
        <label class="attachment-picker">添加附件（最多 5 个，每个 20 MB）<input type="file" multiple :disabled="busy" @change="chooseFiles" /></label><p v-if="files.length">{{ files.map(item => item.name).join('、') }}</p>
        <p v-if="error" class="inline-alert inline-alert--error" role="alert">{{ error }}</p><button class="button button--dark button--full" :disabled="busy || !recipients.length">{{ busy ? '正在发送…' : '发送消息' }}</button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.mail-shortcuts{display:flex;flex-wrap:wrap;gap:20px;font-size:13px}.mail-shortcuts a,.mail-shortcuts button{border:0;background:none;color:var(--green);padding:0;text-decoration:underline;cursor:pointer}
.mail-contacts{border:1px solid var(--line);padding:10px 14px;max-height:210px;overflow:auto;margin:16px 0}.mail-contacts legend{font-size:12px;color:var(--muted);padding:0 6px}.mail-contacts label{display:flex;gap:12px;align-items:center;padding:9px 0;cursor:pointer;font-size:13px}.mail-contacts label:hover{background:var(--hover-paper)}.mail-contacts small{display:block;color:var(--muted);font-size:11px}
.compose-modal{max-height:90vh;overflow:auto}.compose-modal>p{font-size:12px;color:var(--muted)}
</style>
