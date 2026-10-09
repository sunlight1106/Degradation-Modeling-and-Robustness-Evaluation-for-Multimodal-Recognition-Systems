<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
const props = defineProps<{ text: string }>()
const accent = ref('en-US'), slow = ref(false), error = ref(''), speaking = ref(false)
const available = typeof window !== 'undefined' && 'speechSynthesis' in window && 'SpeechSynthesisUtterance' in window
let utterance: SpeechSynthesisUtterance | null = null
function speak() {
  error.value = ''
  if (!available) { error.value = '当前浏览器不支持听读。请使用支持语音朗读的桌面浏览器。'; return }
  window.speechSynthesis.cancel()
  const voice = window.speechSynthesis.getVoices().find(v => v.lang.toLowerCase() === accent.value.toLowerCase())
  utterance = new SpeechSynthesisUtterance(props.text); utterance.lang = accent.value; utterance.rate = slow.value ? .65 : .9
  if (voice) utterance.voice = voice
  utterance.onstart = () => { speaking.value = true }
  utterance.onend = () => { speaking.value = false }
  utterance.onerror = event => { speaking.value = false; if (event.error !== 'canceled' && event.error !== 'interrupted') error.value = '未能播放语音。请检查系统是否安装英文语音。' }
  window.speechSynthesis.speak(utterance)
}
onBeforeUnmount(() => { if (utterance) { utterance.onstart = null; utterance.onend = null; utterance.onerror = null; window.speechSynthesis.cancel() } })
</script>
<template><div class="word-pronunciation"><button type="button" class="table-action" :aria-label="`朗读 ${text}`" @click="speak">{{ speaking ? '重新听读' : '听读' }}</button><select v-model="accent" aria-label="发音口音"><option value="en-US">美音</option><option value="en-GB">英音</option></select><label><input v-model="slow" type="checkbox" /> 慢速</label><small v-if="error" role="status">{{ error }}</small></div></template>
<style scoped>.word-pronunciation{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:10px 0;font-size:12px;color:var(--muted)}select{border:0;background:transparent;color:var(--green);font:inherit;padding:4px;cursor:pointer}label{display:flex;align-items:center;gap:5px;font-size:12px}small{flex-basis:100%;color:var(--muted)}</style>
