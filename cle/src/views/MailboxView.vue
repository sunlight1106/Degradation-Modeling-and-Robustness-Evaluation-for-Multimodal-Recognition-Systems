<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { api, ApiClientError } from '@/api/client'
import type { MessageView, MessageContactView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import IdentityCode from '@/components/IdentityCode.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'

const route = useRoute()
const tab = ref<'inbox' | 'sent'>('inbox')
const messages = ref<MessageView[]>([]), directory = ref<MessageContactView[]>([])
const selected = ref<MessageView | null>(null), composing = ref(false)
const recipients = ref<number[]>([]), subject = ref(''), body = ref(''), query = ref('')
const files = ref<File[]>([]), replyToId = ref<string | undefined>(), replyRecipientId = ref<number | undefined>()
const busy = ref(false), loading = ref(true), error = ref('')
const composeForm = ref<HTMLFormElement | null>(null), contactSearch = ref<HTMLInputElement | null>(null)
const bodyEditor = ref<HTMLTextAreaElement | null>(null), attachmentInput = ref<HTMLInputElement | null>(null)
const selectedContacts = computed(() => directory.value.filter(user => recipients.value.includes(user.id)))
const canSend = computed(() => recipients.value.length > 0 && recipients.value.length <= 20 && !!subject.value.trim() && !!body.value.trim())
let previousOverflow: string | null = null, composeOpener: HTMLElement | null = null
function releaseComposer() {
  if (previousOverflow !== null) { document.body.style.overflow = previousOverflow; previousOverflow = null }
}
function closeComposer() { if (!busy.value) composing.value = false }
function removeRecipient(id: number) { if (!busy.value) recipients.value = recipients.value.filter(value => value !== id) }
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
    if (composing.value) (replyToId.value ? bodyEditor.value : contactSearch.value)?.focus()
  } else {
    releaseComposer()
    await nextTick()
    if (!composing.value && composeOpener?.isConnected) composeOpener.focus()
    composeOpener = null
  }
}, { flush: 'post' })
let epoch = 0, detailEpoch = 0
const unread = computed(() => tab.value === 'inbox' ? messages.value.filter(item => !item.read).length : 0)
const contacts = computed(() => directory.value.filter(user => `${user.displayName} ${user.username} ${user.identityCode || ''}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const relation = { ADMIN: '平台管理员', GROUP_MEMBER: '联系人或同组成员', USER: '平台用户' }
const reasonText = (reason: unknown) => reason instanceof ApiClientError ? reason.message : '操作失败，请重试'
function clearDraft() { recipients.value = []; subject.value = ''; body.value = ''; files.value = []; replyToId.value = undefined; replyRecipientId.value = undefined; query.value = '' }
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
async function open(item: MessageView) {
  const version = epoch, selection = ++detailEpoch
  selected.value = null
  try { const message = await api.message(item.id); if (version === epoch && selection === detailEpoch) { selected.value = message; item.read = true } }
  catch (reason) { if (version === epoch && selection === detailEpoch) error.value = reasonText(reason) }
}
function compose(to?: number, original?: MessageView) {
  if (busy.value || loading.value) return
  if (to && !directory.value.some(user => user.id === to)) { error.value = '此用户已不可联系，请刷新后选择平台管理员或联系人或同组成员。'; return }
  composeOpener = document.activeElement instanceof HTMLElement ? document.activeElement : null
  clearDraft(); if (to) recipients.value = [to]
  if (original) { subject.value = (original.subject.startsWith('回复：') ? original.subject : `回复：${original.subject}`).slice(0, 180); replyToId.value = original.id; replyRecipientId.value = to }
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

    <div v-if="composing" class="modal-backdrop mail-compose-backdrop" @click.self="closeComposer">
      <form ref="composeForm" class="modal-card compose-modal" role="dialog" aria-modal="true" aria-labelledby="mail-compose-title" :aria-busy="busy" tabindex="-1" @keydown="composerKeyboard" @submit.prevent="send">
        <header class="compose-header"><div class="compose-title"><span class="compose-mark"><AppIcon name="edit" :size="21" /></span><div><h3 id="mail-compose-title">{{ replyToId ? '回复站内信' : '写一封站内信' }}</h3><p>{{ replyToId ? '继续这次交流，回复将发给原发件人。' : '分享想法、提出问题，或发送一份资料。' }}</p></div></div><button type="button" class="compose-close" aria-label="关闭写信" :disabled="busy" @click="closeComposer"><AppIcon name="close" :size="20" /></button></header>
        <div class="compose-workspace">
          <aside class="compose-people" aria-label="选择收件人">
            <div class="compose-section-label"><span>联系人</span><small>{{ directory.length }} 人可联系</small></div>
            <label class="compose-search"><AppIcon name="search" :size="16" /><input ref="contactSearch" v-model="query" type="search" aria-label="查找收件人" placeholder="搜索姓名、用户名或身份码" :disabled="busy" /></label>
            <fieldset class="mail-contacts"><legend class="compose-sr-only">选择收件人，已选 {{ recipients.length }} 人，最多 20 人</legend>
              <label v-for="user in contacts" :key="user.id" class="compose-contact" :class="{ 'is-selected': recipients.includes(user.id) }">
                <input v-model="recipients" type="checkbox" :value="user.id" :aria-label="`选择 ${user.displayName} @${user.username}`" :disabled="busy || (recipients.length >= 20 && !recipients.includes(user.id)) || (!!replyToId && user.id !== replyRecipientId)" />
                <span class="compose-avatar" aria-hidden="true">{{ user.displayName.slice(0, 1) }}</span><span class="compose-contact-name"><strong>{{ user.displayName }}</strong><small>@{{ user.username }}</small><IdentityCode :value="user.identityCode" :copyable="false" /><small class="compose-contact-relation">{{ relation[user.relationship] }}</small></span><span class="compose-contact-check" aria-hidden="true"><AppIcon v-if="recipients.includes(user.id)" name="check" :size="14" /></span>
              </label>
              <p v-if="!contacts.length" class="compose-no-contacts">{{ query.trim() ? '没有找到匹配的联系人，试试其他关键词。' : '暂无可联系用户，可刷新联系人后重试。' }}</p>
            </fieldset>
            <p class="compose-selection-count" aria-live="polite">已选择 <strong>{{ recipients.length }}</strong> / 20 人</p>
          </aside>
          <section class="compose-writing" aria-label="信件内容">
            <div class="compose-recipient-line"><span>收件人</span><div class="compose-recipient-chips"><span v-if="!selectedContacts.length" class="compose-recipient-placeholder">从左侧选择联系人</span><span v-for="user in selectedContacts" :key="user.id" class="compose-recipient-chip">{{ user.displayName }}<button type="button" :aria-label="`移除收件人 ${user.displayName}`" :disabled="busy" @click="removeRecipient(user.id)"><AppIcon name="close" :size="13" /></button></span></div></div>
            <label class="compose-subject"><span>主题</span><input v-model="subject" maxlength="180" required :disabled="busy" placeholder="用一句话说明来意" /></label>
            <label class="compose-body"><span class="compose-sr-only">正文</span><textarea ref="bodyEditor" v-model="body" rows="10" maxlength="20000" required :disabled="busy" placeholder="在这里写下你的消息…" /><span class="compose-word-count">{{ body.length.toLocaleString() }} / 20,000</span></label>
            <ul v-if="files.length" class="compose-files" aria-label="已添加附件"><li v-for="(file, index) in files" :key="`${file.name}-${file.lastModified}-${file.size}`"><AppIcon name="file" :size="18" /><span>{{ file.name }}<small>{{ attachmentSize(file.size) }}</small></span><button type="button" :aria-label="`移除附件 ${file.name}`" :disabled="busy" @click="files.splice(index, 1)"><AppIcon name="close" :size="15" /></button></li></ul>
            <p v-if="error" class="compose-error" role="alert">{{ error }}</p>
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
.compose-modal{width:min(940px,calc(100vw - 56px));height:min(690px,calc(100dvh - 56px));max-height:none;padding:0;gap:0;display:grid;grid-template-rows:auto minmax(0,1fr) auto;border:1px solid var(--line);border-radius:6px;background:var(--paper);box-shadow:0 24px 80px #20304024;overflow:hidden;color:var(--ink)}
.compose-header{padding:24px 28px;border-bottom:1px solid var(--line);align-items:center}
.compose-title{display:flex;align-items:center;gap:14px}.compose-mark{width:42px;height:42px;display:grid;place-items:center;background:var(--green-soft);color:var(--green);border-radius:5px}
.compose-modal .compose-title h3{margin:0;font-size:21px;font-weight:550;letter-spacing:.02em}.compose-title p{margin:5px 0 0;color:var(--muted);font-size:12px}
.compose-close{display:grid;place-items:center;width:32px;height:32px;border:0;background:none;color:var(--muted);border-radius:4px;cursor:pointer}.compose-close:hover{background:var(--green-soft);color:var(--green)}
.compose-workspace{display:grid;grid-template-columns:270px minmax(0,1fr);min-height:0}
.compose-people{min-height:0;display:flex;flex-direction:column;padding:23px 16px 18px;border-right:1px solid var(--line);background:var(--canvas)}
.compose-section-label{display:flex;align-items:center;justify-content:space-between;padding:0 8px;font-size:13px}.compose-section-label small{font-size:11px;color:var(--muted)}
.compose-search{display:flex;align-items:center;gap:8px;margin:16px 6px 14px;padding:10px;background:var(--paper);border:1px solid var(--line);border-radius:3px;color:var(--muted)}.compose-search:focus-within{border-color:var(--green)}.compose-search input{width:100%;min-width:0;border:0;padding:0;background:transparent;color:var(--ink);font:inherit;font-size:12px;outline:0}
.compose-modal .mail-contacts{min-height:0;min-width:0;flex:1;overflow:auto;border:0;padding:0;margin:0;scrollbar-width:thin}
.compose-contact{position:relative;display:flex;align-items:center;gap:10px;padding:12px 9px;margin-bottom:4px;border:1px solid transparent;border-radius:4px;cursor:pointer;transition:background .12s,border-color .12s}
.compose-contact:hover{background:var(--hover-paper)}.compose-contact.is-selected{background:var(--green-soft);border-color:color-mix(in srgb,var(--green) 20%,transparent)}.compose-contact:has(input:focus-visible){outline:2px solid var(--green);outline-offset:-2px}.compose-contact:has(input:disabled){opacity:.5;cursor:default}
.compose-contact>input{position:absolute;width:1px;height:1px;opacity:0}.compose-avatar{display:grid;place-items:center;width:32px;height:32px;flex-shrink:0;background:var(--paper);color:var(--green);border:1px solid var(--line);border-radius:50%;font-size:13px}
.compose-contact-name{flex:1;min-width:0}.compose-contact-name strong{display:block;font-size:13px;font-weight:500;overflow-wrap:anywhere}.compose-contact-name small{display:block;font-size:11px;color:var(--muted);overflow-wrap:anywhere;line-height:1.5}.compose-contact-name .compose-contact-relation{font-size:10px;margin-top:2px}.compose-contact-check{width:17px;height:17px;display:grid;place-items:center;border:1px solid var(--line);border-radius:3px;flex-shrink:0;background:var(--paper);color:var(--green)}.is-selected .compose-contact-check{border-color:var(--green)}
.compose-no-contacts{padding:12px;font-size:12px;line-height:1.8;color:var(--muted)}.compose-selection-count{padding:14px 8px 0;margin:0;font-size:11px;color:var(--muted)}.compose-selection-count strong{color:var(--green);font-weight:500}
.compose-writing{padding:0 28px;min-width:0;min-height:0;display:flex;flex-direction:column;overflow:auto;scrollbar-width:thin}
.compose-recipient-line,.compose-subject{display:flex;align-items:baseline;gap:18px;padding:18px 0;border-bottom:1px solid var(--line);font-size:13px;flex-shrink:0}.compose-recipient-line>span,.compose-subject>span{width:44px;flex-shrink:0;color:var(--muted)}.compose-recipient-chips{display:flex;align-items:center;flex-wrap:wrap;gap:7px;min-width:0;max-height:94px;overflow:auto}.compose-recipient-placeholder{color:var(--muted);font-size:12px;padding:3px 0}
.compose-recipient-chip{display:inline-flex;align-items:center;gap:7px;max-width:100%;overflow-wrap:anywhere;background:var(--green-soft);color:var(--green);border-radius:3px;padding:3px 7px;font-size:12px;line-height:1.7}.compose-recipient-chip button,.compose-files button{display:grid;place-items:center;border:0;padding:2px;background:none;color:var(--muted);cursor:pointer;flex-shrink:0}.compose-recipient-chip button:hover,.compose-files button:hover{color:var(--green)}
.compose-subject input{width:100%;min-width:0;height:auto;border:0;outline:0;padding:0;background:transparent;font:inherit;font-size:15px;color:var(--ink)}.compose-subject:focus-within{border-bottom-color:var(--green)}
.compose-body{position:relative;display:flex;flex-direction:column;flex:1;min-height:180px;margin:20px 0 14px}.compose-modal .compose-body textarea{flex:1;min-height:150px;width:100%;padding:0 0 25px;border:0;outline:0;resize:none;background:transparent;color:var(--ink);font:inherit;font-size:14px;line-height:1.9}.compose-body:focus-within{box-shadow:inset 2px 0 var(--green-soft)}.compose-word-count{align-self:flex-end;font-size:10px;color:var(--muted);font-variant-numeric:tabular-nums}
.compose-files{display:flex;gap:8px;flex-wrap:wrap;margin:0 0 16px;padding:0;list-style:none;flex-shrink:0}.compose-files li{display:flex;align-items:center;gap:10px;padding:8px 10px;border:1px solid var(--line);border-radius:4px;background:var(--canvas);font-size:12px;max-width:100%}.compose-files li>span{overflow-wrap:anywhere;min-width:0}.compose-files small{display:block;font-size:10px;color:var(--muted)}.compose-error{padding:10px 12px;background:var(--canvas);border-left:2px solid #aa5860;color:var(--ink);font-size:12px;margin:0 0 16px;flex-shrink:0}
.compose-footer{padding:18px 28px;border-top:1px solid var(--line);display:flex;justify-content:space-between;align-items:center;gap:20px;background:var(--paper)}.compose-attachment-action,.compose-actions{display:flex;align-items:center;gap:18px}.compose-attachment-action small{font-size:10px;color:var(--muted)}.compose-attach{display:inline-flex;align-items:center;gap:8px;border:0;background:none;color:var(--green);font:inherit;font-size:12px;padding:6px 0;cursor:pointer}.compose-attach:hover{color:var(--ink)}.compose-attach span{font-size:10px;color:var(--muted)}.compose-cancel{border:0;background:none;color:var(--muted);font:inherit;font-size:12px;padding:9px 12px;cursor:pointer}.compose-cancel:hover{color:var(--ink)}.compose-modal .compose-send{min-width:128px;padding:10px 18px;gap:12px;border-radius:3px;font-size:13px;height:auto}.compose-modal button:disabled{cursor:default;opacity:.5}
.compose-sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
@media(max-height:620px){.compose-header{padding:15px 24px}.compose-footer{padding:12px 24px}.compose-body{min-height:100px}.compose-modal .compose-body textarea{min-height:85px}}
@media(prefers-reduced-motion:reduce){.compose-contact{transition:none}}
</style>
