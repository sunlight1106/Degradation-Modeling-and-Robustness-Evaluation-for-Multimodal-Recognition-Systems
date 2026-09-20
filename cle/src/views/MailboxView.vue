<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiClientError } from '@/api/client'
import type { MessageView, UserDirectoryView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import { toastStore } from '@/stores/toast'

const tab = ref<'inbox' | 'sent'>('inbox')
const messages = ref<MessageView[]>([])
const directory = ref<UserDirectoryView[]>([])
const selected = ref<MessageView | null>(null)
const composing = ref(false)
const recipients = ref<number[]>([])
const subject = ref('')
const body = ref('')
const files = ref<File[]>([])
const busy = ref(false)
const loading = ref(true)
const error = ref('')
const unread = computed(() => messages.value.filter(item => !item.read).length)

async function load() {
  loading.value = true; error.value = ''
  try {
    const [items, users] = await Promise.all([tab.value === 'inbox' ? api.inbox() : api.sent(), api.messageDirectory()])
    messages.value = items; directory.value = users
  } catch (reason) { error.value = reason instanceof ApiClientError ? reason.message : '站内信加载失败' }
  finally { loading.value = false }
}

async function switchTab(next: 'inbox' | 'sent') { tab.value = next; selected.value = null; await load() }
async function open(item: MessageView) { selected.value = await api.message(item.id); item.read = true }
function chooseFiles(event: Event) { files.value = Array.from((event.target as HTMLInputElement).files || []).slice(0, 5) }
async function send() {
  if (!recipients.value.length || !subject.value.trim() || !body.value.trim()) return
  busy.value = true
  try {
    await api.sendMessage({ recipientIds: recipients.value, subject: subject.value, body: body.value, files: files.value })
    composing.value = false; recipients.value = []; subject.value = ''; body.value = ''; files.value = []
    toastStore.success('站内信已发送'); await switchTab('sent')
  } catch (reason) { toastStore.error(reason instanceof ApiClientError ? reason.message : '发送失败') }
  finally { busy.value = false }
}

onMounted(load)
</script>

<template>
  <div class="page-stack mailbox-page">
    <section class="page-intro page-intro--split"><div><p class="page-kicker">COLLABORATION</p><h2>站内信箱</h2><p>向平台用户发送文字与附件，文件沿用对象存储和病毒扫描流程。</p></div><button class="button button--dark" @click="composing = true"><AppIcon name="plus" :size="17" /> 写信</button></section>
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
        <template v-if="selected"><p class="page-kicker">{{ tab === 'inbox' ? 'INBOX' : 'SENT' }}</p><h3>{{ selected.subject }}</h3><div class="mail-meta"><b>{{ selected.senderName }}</b><span>发送给 {{ selected.recipients.map(r => r.displayName).join('、') }}</span><time>{{ new Date(selected.createdAt).toLocaleString('zh-CN') }}</time></div><p class="mail-body">{{ selected.body }}</p><div v-if="selected.attachments.length" class="mail-attachments"><button v-for="file in selected.attachments" :key="file.id" @click="api.download(file.downloadUrl, file.fileName)"><AppIcon name="file" :size="18" /><span>{{ file.fileName }}<small>{{ Math.ceil(file.sizeBytes / 1024) }} KB</small></span><AppIcon name="download" :size="16" /></button></div></template>
        <EmptyState v-else title="选择一封消息" description="邮件内容和附件会显示在这里。" icon="mail" />
      </article>
    </section>

    <div v-if="composing" class="modal-backdrop" @click.self="composing = false"><form class="modal-card compose-modal" @submit.prevent="send"><header><div><p class="page-kicker">NEW MESSAGE</p><h3>写站内信</h3></div><button type="button" class="icon-button" @click="composing = false"><AppIcon name="close" /></button></header><label class="field-label">收件人<select v-model="recipients" class="field-input" multiple size="4" required><option v-for="user in directory" :key="user.id" :value="user.id">{{ user.displayName }} · @{{ user.username }}</option></select></label><label class="field-label">主题<input v-model="subject" class="field-input" maxlength="180" required /></label><label class="field-label">正文<textarea v-model="body" class="field-input" rows="7" maxlength="20000" required /></label><label class="attachment-picker"><AppIcon name="upload" :size="18" /> 添加附件（最多 5 个）<input type="file" multiple @change="chooseFiles" /></label><p v-if="files.length">{{ files.map(item => item.name).join('、') }}</p><button class="button button--dark button--full" :disabled="busy">{{ busy ? '正在发送…' : '发送消息' }}</button></form></div>
  </div>
</template>
