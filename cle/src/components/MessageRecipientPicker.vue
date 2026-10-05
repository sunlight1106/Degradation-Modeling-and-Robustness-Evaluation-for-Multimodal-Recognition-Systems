<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { api, ApiClientError } from '@/api/client'
import type { MessageContactView } from '@/types/api'
import AppIcon from '@/components/AppIcon.vue'

const props = defineProps<{ modelValue: MessageContactView[]; disabled?: boolean; restrictedContact?: MessageContactView }>()
const emit = defineEmits<{ 'update:modelValue': [MessageContactView[]] }>()
const root = ref<HTMLElement | null>(null), input = ref<HTMLInputElement | null>(null), list = ref<HTMLElement | null>(null)
const opened = ref(false), query = ref(''), rows = ref<MessageContactView[]>([]), loading = ref(false), error = ref('')
const hasMore = ref(false), active = ref(-1), listId = `mail-recipients-${useId()}`
const choices = computed(() => props.restrictedContact ? [props.restrictedContact] : rows.value)
const selected = (id: number) => props.modelValue.some(user => user.id === id)
const unavailable = (id: number) => !!props.disabled || props.modelValue.length >= 20 && !selected(id)
const relation = { ADMIN: '管理员', GROUP_MEMBER: '联系人 / 同组成员', USER: '平台用户' }
let version = 0, page = 0, timer: ReturnType<typeof setTimeout> | undefined

async function search(append = false) {
  if (props.restrictedContact || props.disabled || append && (!hasMore.value || loading.value)) return
  const request = ++version, nextPage = append ? page + 1 : 0
  loading.value = true; error.value = ''
  if (!append) { rows.value = []; hasMore.value = false; active.value = -1 }
  try {
    const result = await api.messageDirectory({ q: query.value.trim(), page: nextPage })
    if (request !== version) return
    rows.value = append ? [...new Map([...rows.value, ...result].map(user => [user.id, user])).values()] : result
    page = nextPage; hasMore.value = result.length === 25
  } catch (reason) { if (request === version) error.value = reason instanceof ApiClientError ? reason.message : '联系人加载失败，请重试。' }
  finally { if (request === version) loading.value = false }
}
function open() {
  if (opened.value || props.disabled) return
  opened.value = true; active.value = -1
  void search()
}
function close() { opened.value = false; active.value = -1 }
function toggle(user: MessageContactView) {
  if (unavailable(user.id)) return
  emit('update:modelValue', selected(user.id) ? props.modelValue.filter(item => item.id !== user.id) : [...props.modelValue, user])
}
function remove(id: number) { if (!props.disabled) emit('update:modelValue', props.modelValue.filter(user => user.id !== id)) }
function focus() { input.value?.focus() }
function toggleOpen() { if (opened.value) close(); else { open(); focus() } }
async function keyboard(event: KeyboardEvent) {
  if (event.isComposing) return
  if (event.key === 'Escape' && opened.value) { event.preventDefault(); event.stopPropagation(); focus(); close(); return }
  if (event.key === 'Tab') { close(); return }
  if (event.target !== input.value) return
  if (event.key === 'Enter') {
    event.preventDefault()
    if (!opened.value) open()
    else if (active.value >= 0 && choices.value[active.value]) toggle(choices.value[active.value]!)
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  if (['Home', 'End'].includes(event.key) && !opened.value) return
  event.preventDefault(); open()
  const length = choices.value.length
  if (!length) return
  active.value = event.key === 'Home' ? 0 : event.key === 'End' ? length - 1 : event.key === 'ArrowDown' ? (active.value + 1) % length : (active.value < 0 ? length - 1 : (active.value - 1 + length) % length)
  await nextTick()
  list.value?.querySelector<HTMLElement>(`[id="${listId}-${active.value}"]`)?.scrollIntoView?.({ block: 'nearest' })
}
function outside(event: PointerEvent) { if (!root.value?.contains(event.target as Node)) close() }
function blur(event: FocusEvent) { if (!root.value?.contains(event.relatedTarget as Node)) close() }
watch(query, () => {
  clearTimeout(timer); version++; loading.value = false; rows.value = []; hasMore.value = false; active.value = -1; error.value = ''
  if (opened.value) timer = setTimeout(() => void search(), 250)
}, { flush: 'sync' })
watch(() => props.disabled, disabled => { if (disabled) close() })
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => { version++; clearTimeout(timer); document.removeEventListener('pointerdown', outside) })
defineExpose({ focus })
</script>

<template>
  <div ref="root" class="recipient-picker" @focusout="blur" @keydown="keyboard">
    <div class="recipient-heading"><label :for="`${listId}-input`">收件人</label><span aria-live="polite">已选 {{ modelValue.length }} / 20</span></div>
    <div v-if="modelValue.length" class="recipient-chips" aria-label="已选收件人">
      <span v-for="user in modelValue" :key="user.id" class="recipient-chip"><span><strong>{{ user.displayName }}</strong><code>{{ user.identityCode }}</code></span><button type="button" :aria-label="`移除收件人 ${user.displayName} ${user.identityCode}`" :disabled="disabled" @click="remove(user.id)"><AppIcon name="close" :size="14" /></button></span>
    </div>
    <div class="recipient-field" :class="{ 'is-open': opened }">
      <AppIcon name="search" :size="16" />
      <input :id="`${listId}-input`" ref="input" v-model="query" type="text" role="combobox" aria-label="查找收件人" :aria-expanded="opened" :aria-controls="listId" :aria-activedescendant="opened && active >= 0 ? `${listId}-${active}` : undefined" aria-autocomplete="list" autocomplete="off" maxlength="80" placeholder="输入名称、用户名或完整身份码" :disabled="disabled" :readonly="!!restrictedContact" @focus="open" @click="open" />
      <button type="button" :aria-label="opened ? '收起收件人' : '展开收件人'" :aria-expanded="opened" :disabled="disabled" @mousedown.prevent @click="toggleOpen"><AppIcon name="chevron" :style="{ transform: opened ? 'rotate(-90deg)' : 'rotate(90deg)' }" :size="16" /></button>
    </div>
    <p class="recipient-help">{{ restrictedContact ? '回复仅发送给原发件人。' : '名称可重复，请用系统分配的唯一身份码核对收件人。' }}</p>
    <div v-if="opened" class="recipient-dropdown">
      <div ref="list" :id="listId" class="recipient-options" role="listbox" aria-label="可联系的收件人" aria-multiselectable="true" :aria-busy="loading">
        <button v-for="(user, index) in choices" :id="`${listId}-${index}`" :key="user.id" type="button" role="option" :aria-selected="selected(user.id)" :aria-disabled="unavailable(user.id)" :disabled="unavailable(user.id)" tabindex="-1" class="recipient-option" :class="{ 'is-active': index === active, 'is-selected': selected(user.id) }" @mousedown.prevent @click="toggle(user)">
          <span class="recipient-avatar" aria-hidden="true">{{ user.displayName.slice(0, 1) }}</span><span class="recipient-identity"><span><strong>{{ user.displayName }}</strong><small>@{{ user.username }} · {{ relation[user.relationship] }}</small></span><code>{{ user.identityCode }}</code></span><AppIcon v-if="selected(user.id)" name="check" :size="16" /><span v-else class="recipient-check" aria-hidden="true" />
        </button>
      </div>
      <p v-if="error" class="recipient-status" role="alert">{{ error }} <button type="button" @click="search(rows.length > 0)">重试</button></p>
      <p v-else-if="loading" class="recipient-status" role="status">正在查找…</p>
      <p v-else-if="!choices.length" class="recipient-status">没有找到可联系的用户，请核对名称或完整身份码。</p>
      <button v-else-if="hasMore && !restrictedContact" type="button" class="recipient-more" @click="search(true)">加载更多收件人</button>
      <p v-else class="recipient-status">{{ choices.length ? '已显示全部匹配用户' : '' }}</p>
    </div>
  </div>
</template>

<style scoped>
.recipient-picker{position:relative;padding:20px 0 16px;border-bottom:1px solid var(--line);flex-shrink:0;z-index:2}
.recipient-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;font-size:13px}.recipient-heading span{color:var(--muted);font-size:11px}
.recipient-field{display:flex;align-items:center;gap:10px;padding:10px 12px;border:1px solid var(--line);border-radius:4px;color:var(--muted);background:var(--paper)}.recipient-field:focus-within,.recipient-field.is-open{border-color:var(--green)}.recipient-field input{width:100%;min-width:0;padding:0;border:0;background:none;color:var(--ink);outline:0;font:inherit;font-size:13px}.recipient-field button,.recipient-chip button{border:0;background:none;color:var(--muted);display:grid;place-items:center;padding:2px;cursor:pointer}.recipient-help{font-size:11px;color:var(--muted);margin:9px 0 0}
.recipient-chips{display:flex;flex-wrap:wrap;gap:6px;max-height:100px;overflow:auto;margin-bottom:10px}.recipient-chip{display:flex;align-items:center;gap:12px;padding:6px 9px;background:var(--green-soft);border:1px solid var(--line);border-radius:3px;max-width:100%}.recipient-chip strong{font-size:12px;font-weight:500}.recipient-chip code{display:block;font-size:10px;color:var(--muted);overflow-wrap:anywhere}.recipient-chip button{flex-shrink:0}
.recipient-dropdown{position:absolute;top:calc(100% - 9px);left:0;right:0;background:var(--paper);border:1px solid var(--line);border-radius:4px;box-shadow:0 10px 28px #2030401c;overflow:hidden}.recipient-options{max-height:252px;overflow:auto;overscroll-behavior:contain;scrollbar-width:thin}.recipient-option{width:100%;display:flex;gap:12px;align-items:center;text-align:left;padding:12px 14px;border:0;border-bottom:1px solid var(--line);background:none;color:var(--ink);cursor:pointer}.recipient-option:last-child{border-bottom:0}.recipient-option:hover,.recipient-option.is-active{background:var(--hover-paper)}.recipient-option.is-selected{background:var(--green-soft)}.recipient-option:disabled{opacity:.5;cursor:default}.recipient-avatar{width:30px;height:30px;display:grid;place-items:center;border:1px solid var(--line);border-radius:50%;font-size:12px;flex-shrink:0}.recipient-identity{flex:1;min-width:0}.recipient-identity>span{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap}.recipient-identity strong{font-size:13px;font-weight:500;overflow-wrap:anywhere}.recipient-identity small{font-size:11px;color:var(--muted);overflow-wrap:anywhere}.recipient-identity code{display:block;margin-top:5px;color:var(--green);font-size:11px;overflow-wrap:anywhere}.recipient-check{width:15px;height:15px;border:1px solid var(--line);border-radius:3px;flex-shrink:0}.recipient-status{padding:10px 14px;margin:0;color:var(--muted);font-size:11px;line-height:1.7}.recipient-status button,.recipient-more{border:0;background:none;color:var(--green);cursor:pointer;font:inherit}.recipient-more{width:100%;padding:12px;font-size:12px;border-top:1px solid var(--line)}.recipient-more:hover{background:var(--hover-paper)}
</style>
