<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type { MessageView, MessageContactView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import MessageRecipientPicker from '@/components/MessageRecipientPicker.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'

const route = useRoute()
const tab = ref<'inbox' | 'sent'>('inbox')
const messages = ref<MessageView[]>([]), directory = ref<MessageContactView[]>([])
const selected = ref<MessageView | null>(null), composing = ref(false)
const selectedContacts = ref<MessageContactView[]>([]), subject = ref(''), body = ref('')
const recipients = computed(() => selectedContacts.value.map(user => user.id))
const files = ref<File[]>([]), replyToId = ref<string | undefined>(), replyContact = ref<MessageContactView | undefined>()
const busy = ref(false), loading = ref(true), error = ref('')
const composeForm = ref<HTMLFormElement | null>(null), recipientPicker = ref<InstanceType<typeof MessageRecipientPicker> | null>(null)
const bodyEditor = ref<HTMLTextAreaElement | null>(null), attachmentInput = ref<HTMLInputElement | null>(null)
const canSend = computed(() => recipients.value.length > 0 && recipients.value.length <= 20 && !!subject.value.trim() && !!body.value.trim())
let previousOverflow: string | null = null, composeOpener: HTMLElement | null = null
function releaseComposer() {
  if (previousOverflow !== null) { document.body.style.overflow = previousOverflow; previousOverflow = null }
}
function closeComposer() { if (!busy.value) composing.value = false }
function attachmentSize(size: number) { return size >= 1024 * 1024 ? `${(size / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.ceil(size / 1024))} KB` }
function composerKeyboard(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); closeComposer(); return }
  if (event.key !== 'Tab') return
  const controls = Array.from(composeForm.value?.querySelectorAll<HTMLElement>('button:not([disabled]),input:not([disabled]),textarea:not([disabled]),[tabindex="0"]') || []).filter(node => node.tabIndex >= 0 && node.getClientRects().length > 0)
  const first = controls[0], last = controls.at(-1)
  if (!first) { event.preventDefault(); composeForm.value?.focus(); return }
  if (event.shiftKey && (document.activeElement === first || document.activeElement === composeForm.value)) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
watch(composing, async opened => {
  if (opened) {
    if (previousOverflow === null) previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    await nextTick()
    if (composing.value) (replyToId.value ? bodyEditor.value : recipientPicker.value)?.focus()
  } else {
    releaseComposer()
    await nextTick()
    if (!composing.value && composeOpener?.isConnected) composeOpener.focus()
    composeOpener = null
  }
}, { flush: 'post' })
let epoch = 0, detailEpoch = 0
const unread = computed(() => tab.value === 'inbox' ? messages.value.filter(item => !item.read).length : 0)
const reasonText = (reason: unknown) => reason instanceof ApiClientError ? reason.message : '操作失败，请重试'
function clearDraft() { selectedContacts.value = []; subject.value = ''; body.value = ''; files.value = []; replyToId.value = undefined; replyContact.value = undefined }
function reset() { releaseComposer(); composeOpener = null; epoch++; detailEpoch++; messages.value = []; directory.value = []; selected.value = null; composing.value = false; clearDraft(); error.value = ''; busy.value = false; loading.value = false }
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
async function openNotification() {
  const id = route.query.message
  if (typeof id !== 'string' || composing.value || busy.value) return
  const version = epoch, selection = ++detailEpoch
  selected.value = null
  try {
    const message = await api.message(id)
    if (version === epoch && selection === detailEpoch) {
      selected.value = message
      const item = messages.value.find(m => m.id === id)
      if (item) item.read = true
    }
  } catch (reason) { if (version === epoch && selection === detailEpoch) error.value = reasonText(reason) }
}
watch(() => route.query.message, openNotification)
async function open(item: MessageView) {
  const version = epoch, selection = ++detailEpoch
  selected.value = null
  try { const message = await api.message(item.id); if (version === epoch && selection === detailEpoch) { selected.value = message; item.read = true } }
  catch (reason) { if (version === epoch && selection === detailEpoch) error.value = reasonText(reason) }
}
async function compose(to?: number, original?: MessageView) {
  if (busy.value || loading.value) return
  const version = epoch
  composeOpener = document.activeElement instanceof HTMLElement ? document.activeElement : null
  let contact: MessageContactView | undefined
  if (to) {
    busy.value = true
    try {
      contact = (await api.messageDirectory({ userId: to }))[0]
      if (version !== epoch) return
      if (!contact) { error.value = '此用户已不可联系，请刷新后选择管理员、联系人或同组成员。'; return }
    } catch (reason) { if (version === epoch) error.value = reasonText(reason); return }
    finally { if (version === epoch) busy.value = false }
  }
  clearDraft(); if (contact) selectedContacts.value = [contact]
  if (original) { subject.value = (original.subject.startsWith('回复：') ? original.subject : `回复：${original.subject}`).slice(0, 180); replyToId.value = original.id; replyContact.value = contact }
  composing.value = true; error.value = ''
}
function chooseFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const merged = [...files.value, ...Array.from(input.files || [])].filter((file, index, all) => all.findIndex(item => item.name === file.name && item.size === file.size && item.lastModified === file.lastModified) === index)
  input.value = ''
  if (merged.length > 5 || merged.some(file => file.size > 20 * 1024 * 1024)) { error.value = '最多添加 5 个附件，每个不超过 20 MB。已选附件仍保留。'; return }
  files.value = merged; error.value = ''
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
function liveRefresh(){if(!busy.value&&!composing.value)void load()}
onMounted(()=>window.addEventListener("pkb:live-update",liveRefresh))
onBeforeUnmount(()=>window.removeEventListener("pkb:live-update",liveRefresh))
onMounted(async () => { const version = epoch + 1; await load(); if (epoch === version) { if (route.query.to) compose(Number(route.query.to)); else void openNotification() } })
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

    <div v-if="composing" class="modal-backdrop mail-compose-backdrop" @click.self="closeComposer">
      <form ref="composeForm" class="modal-card compose-modal" role="dialog" aria-modal="true" aria-labelledby="mail-compose-title" :aria-busy="busy" tabindex="-1" @keydown="composerKeyboard" @submit.prevent="send">
        <header class="compose-header"><div class="compose-title"><span class="compose-mark"><AppIcon name="edit" :size="21" /></span><div><h3 id="mail-compose-title">{{ replyToId ? '回复站内信' : '写一封站内信' }}</h3><p>{{ replyToId ? '继续这次交流，回复将发给原发件人。' : '分享想法、提出问题，或发送一份资料。' }}</p></div></div><button type="button" class="compose-close" aria-label="关闭写信" :disabled="busy" @click="closeComposer"><AppIcon name="close" :size="20" /></button></header>
        <div class="compose-workspace">
          <section class="compose-writing" aria-label="信件内容">
            <MessageRecipientPicker ref="recipientPicker" v-model="selectedContacts" :disabled="busy" :restricted-contact="replyContact" />
            <div class="compose-editor"><label class="compose-subject"><span>主题</span><input v-model="subject" maxlength="180" required :disabled="busy" placeholder="用一句话说明来意" /></label>
            <label class="compose-body"><span class="compose-sr-only">正文</span><textarea ref="bodyEditor" v-model="body" rows="10" maxlength="20000" required :disabled="busy" placeholder="在这里写下你的消息…" /><span class="compose-word-count">{{ body.length.toLocaleString() }} / 20,000</span></label>
            <ul v-if="files.length" class="compose-files" aria-label="已添加附件"><li v-for="(file, index) in files" :key="`${file.name}-${file.lastModified}-${file.size}`"><AppIcon name="file" :size="18" /><span>{{ file.name }}<small>{{ attachmentSize(file.size) }}</small></span><button type="button" :aria-label="`移除附件 ${file.name}`" :disabled="busy" @click="files.splice(index, 1)"><AppIcon name="close" :size="15" /></button></li></ul>
            <p v-if="error" class="compose-error" role="alert">{{ error }}</p></div>
          </section>
        </div>
        <footer class="compose-footer"><div class="compose-attachment-action"><input ref="attachmentInput" class="compose-sr-only" type="file" multiple tabindex="-1" aria-label="添加附件" :disabled="busy" @change="chooseFiles" /><button type="button" class="compose-attach" :disabled="busy || files.length >= 5" @click="attachmentInput?.click()"><AppIcon name="upload" :size="17" /> 添加附件<span v-if="files.length">{{ files.length }}/5</span></button><small>最多 5 个 · 每个 20 MB</small></div><div class="compose-actions"><button type="button" class="compose-cancel" :disabled="busy" @click="closeComposer">取消</button><button class="button button--dark compose-send" :disabled="busy || !canSend">{{ busy ? '正在发送…' : '发送站内信' }}<AppIcon name="arrow" :size="16" /></button></div></footer>
      </form>
    </div>
  </div>
</template>

<style scoped>
.mail-shortcuts{display:flex;flex-wrap:wrap;gap:20px;font-size:13px}.mail-shortcuts a,.mail-shortcuts button{border:0;background:none;color:var(--green);padding:0;text-decoration:underline;cursor:pointer}
.mail-compose-backdrop{background:rgba(29,43,54,.2);backdrop-filter:blur(3px);padding:28px}
.compose-modal{width:min(880px,calc(100vw - 56px));height:min(690px,calc(100dvh - 56px));max-height:none;padding:0;gap:0;display:grid;grid-template-rows:auto minmax(0,1fr) auto;border:1px solid var(--line);border-radius:6px;background:var(--paper);box-shadow:0 24px 80px #20304024;overflow:hidden;color:var(--ink)}
.compose-header{padding:24px 28px;border-bottom:1px solid var(--line);align-items:center}
.compose-title{display:flex;align-items:center;gap:14px}.compose-mark{width:42px;height:42px;display:grid;place-items:center;background:var(--green-soft);color:var(--green);border-radius:5px}
.compose-modal .compose-title h3{margin:0;font-size:21px;font-weight:550;letter-spacing:.02em}.compose-title p{margin:5px 0 0;color:var(--muted);font-size:12px}
.compose-close{display:grid;place-items:center;width:32px;height:32px;border:0;background:none;color:var(--muted);border-radius:4px;cursor:pointer}.compose-close:hover{background:var(--green-soft);color:var(--green)}
.compose-workspace{display:grid;grid-template-columns:minmax(0,1fr);min-height:0}
.compose-writing{padding:0 28px;min-width:0;min-height:0;display:flex;flex-direction:column;overflow:visible}
.compose-editor{display:flex;flex-direction:column;min-height:0;flex:1;overflow:auto;scrollbar-width:thin}
.compose-subject{display:flex;align-items:baseline;gap:18px;padding:18px 0;border-bottom:1px solid var(--line);font-size:13px;flex-shrink:0}.compose-subject>span{width:44px;flex-shrink:0;color:var(--muted)}
.compose-subject input{width:100%;min-width:0;height:auto;border:0;outline:0;padding:0;background:transparent;font:inherit;font-size:15px;color:var(--ink)}.compose-subject:focus-within{border-bottom-color:var(--green)}
.compose-body{position:relative;display:flex;flex-direction:column;flex:1;min-height:180px;margin:20px 0 14px}.compose-modal .compose-body textarea{flex:1;min-height:150px;width:100%;padding:0 0 25px;border:0;outline:0;resize:none;background:transparent;color:var(--ink);font:inherit;font-size:14px;line-height:1.9}.compose-body:focus-within{box-shadow:inset 2px 0 var(--green-soft)}.compose-word-count{align-self:flex-end;font-size:10px;color:var(--muted);font-variant-numeric:tabular-nums}
.compose-files{max-height:85px;overflow:auto;display:flex;gap:8px;flex-wrap:wrap;margin:0 0 16px;padding:0;list-style:none;flex-shrink:0}.compose-files li{display:flex;align-items:center;gap:10px;padding:8px 10px;border:1px solid var(--line);border-radius:4px;background:var(--canvas);font-size:12px;max-width:100%}.compose-files li>span{overflow-wrap:anywhere;min-width:0}.compose-files small{display:block;font-size:10px;color:var(--muted)}.compose-error{padding:10px 12px;background:var(--canvas);border-left:2px solid #aa5860;color:var(--ink);font-size:12px;margin:0 0 16px;flex-shrink:0}
.compose-files button{display:grid;place-items:center;border:0;padding:2px;background:none;color:var(--muted);cursor:pointer;flex-shrink:0}.compose-files button:hover{color:var(--green)}
.compose-footer{padding:18px 28px;border-top:1px solid var(--line);display:flex;justify-content:space-between;align-items:center;gap:20px;background:var(--paper)}.compose-attachment-action,.compose-actions{display:flex;align-items:center;gap:18px}.compose-attachment-action small{font-size:10px;color:var(--muted)}.compose-attach{display:inline-flex;align-items:center;gap:8px;border:0;background:none;color:var(--green);font:inherit;font-size:12px;padding:6px 0;cursor:pointer}.compose-attach:hover{color:var(--ink)}.compose-attach span{font-size:10px;color:var(--muted)}.compose-cancel{border:0;background:none;color:var(--muted);font:inherit;font-size:12px;padding:9px 12px;cursor:pointer}.compose-cancel:hover{color:var(--ink)}.compose-modal .compose-send{min-width:128px;padding:10px 18px;gap:12px;border-radius:3px;font-size:13px;height:auto}.compose-modal button:disabled{cursor:default;opacity:.5}
.compose-sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
@media(max-height:620px){.compose-header{padding:15px 24px}.compose-footer{padding:12px 24px}.compose-body{min-height:100px}.compose-modal .compose-body textarea{min-height:85px}}
</style>
