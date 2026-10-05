<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
import { toastStore } from '@/stores/toast'

const props = withDefaults(defineProps<{ value?: string; copyable?: boolean; prominent?: boolean }>(), { copyable: true, prominent: false })
async function copy() {
  if (!props.value) return
  try { await navigator.clipboard.writeText(props.value); toastStore.success('身份码已复制') }
  catch { toastStore.error('无法自动复制，请选中身份码后复制') }
}
</script>

<template>
  <span v-if="value" class="identity-code" :class="{ 'identity-code--prominent': prominent }" :title="`身份码 ${value}`"><code>{{ value }}</code><button v-if="copyable" type="button" aria-label="复制身份码" @click.stop="copy"><AppIcon name="copy" :size="14" /></button></span>
</template>

<style scoped>
.identity-code{display:inline-flex;align-items:center;gap:8px;max-width:100%;min-width:0;vertical-align:middle;color:var(--muted)}
.identity-code code{font:11px/1.6 ui-monospace,SFMono-Regular,Consolas,monospace;background:none;padding:0;letter-spacing:.01em;overflow-wrap:anywhere;user-select:text;min-width:0}
.identity-code button{display:inline-grid;place-items:center;padding:4px;flex-shrink:0;color:var(--muted);border:0;border-radius:3px;background:transparent;cursor:pointer}
.identity-code button:hover{color:var(--green);background:var(--green-soft)}
.identity-code button:focus-visible{outline:2px solid var(--green);outline-offset:2px}
.identity-code--prominent{width:fit-content;padding:9px 12px;gap:16px;border:1px solid var(--line);border-radius:3px;background:var(--paper);color:var(--ink)}
.identity-code--prominent code{font-size:14px}
</style>
