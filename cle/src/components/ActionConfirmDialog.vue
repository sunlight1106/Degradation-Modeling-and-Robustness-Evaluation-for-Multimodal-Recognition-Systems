<script setup lang="ts">
import { nextTick, onMounted, onBeforeUnmount, ref } from 'vue'
const props = defineProps<{ title: string; description: string; confirmLabel: string; busy?: boolean; error?: string }>()
const emit = defineEmits<{ cancel: []; confirm: [] }>()
const dialog = ref<HTMLElement | null>(null), cancelButton = ref<HTMLButtonElement | null>(null)
let opener: HTMLElement | null = null, overflow = ''
function cancel() { if (!props.busy) emit('cancel') }
function keyboard(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); cancel() }
  if (event.key !== 'Tab') return
  const buttons = [...dialog.value!.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')]
  if (!buttons.length) { event.preventDefault(); return }
  if (event.shiftKey && document.activeElement === buttons[0]) { event.preventDefault(); buttons.at(-1)?.focus() }
  else if (!event.shiftKey && document.activeElement === buttons.at(-1)) { event.preventDefault(); buttons[0]?.focus() }
}
onMounted(async () => { opener = document.activeElement as HTMLElement; overflow = document.body.style.overflow; document.body.style.overflow = 'hidden'; await nextTick(); cancelButton.value?.focus() })
onBeforeUnmount(() => { document.body.style.overflow = overflow; if (opener?.isConnected) opener.focus() })
</script>

<template>
  <Teleport to="body"><div class="action-confirm-backdrop" @click.self="cancel"><section ref="dialog" class="action-confirm" role="alertdialog" aria-modal="true" aria-labelledby="contact-action-title" aria-describedby="contact-action-description" :aria-busy="busy" @keydown="keyboard"><h3 id="contact-action-title">{{ title }}</h3><p id="contact-action-description">{{ description }}</p><p v-if="error" role="alert">{{ error }}</p><footer><button ref="cancelButton" type="button" class="button button--ghost" :disabled="busy" @click="cancel">取消</button><button type="button" class="button button--dark" :disabled="busy" @click="emit('confirm')">{{ busy ? '正在处理…' : confirmLabel }}</button></footer></section></div></Teleport>
</template>

<style scoped>
.action-confirm-backdrop{position:fixed;inset:0;z-index:110;display:grid;place-items:center;padding:24px;background:#1d2b3633;backdrop-filter:blur(3px)}
.action-confirm{width:min(440px,100%);padding:30px;background:var(--paper);color:var(--ink);border:1px solid var(--line);border-radius:5px;box-shadow:0 20px 70px #20304024}
.action-confirm h3{font-size:20px;font-weight:550;margin:0 0 16px}.action-confirm p{font-size:14px;line-height:1.9;color:var(--muted);white-space:pre-line;margin:0 0 28px}.action-confirm footer{display:flex;justify-content:flex-end;gap:10px}.action-confirm .button{font-size:13px;padding:9px 16px}
</style>
