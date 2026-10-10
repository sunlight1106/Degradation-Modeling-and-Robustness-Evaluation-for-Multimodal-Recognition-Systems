<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { wordSpeaker } from '@/lib/pronunciation'
const props = defineProps<{ text: string;conceal?:boolean }>()
const accent = ref('en-US'), slow = ref(false), error = ref(''), speaking = ref(false)
const speaker = wordSpeaker()
function speak() { error.value = ''; speaker.say(props.text, accent.value, slow.value, { start: () => speaking.value = true, stop: () => speaking.value = false, error: message => error.value = message }) }
onBeforeUnmount(() => speaker.stop())
</script>
<template><div class="word-pronunciation"><button type="button" class="table-action" :aria-label="conceal?'播放本题语音':`朗读 ${text}`" @click="speak">{{ speaking ? '重新听读' : '听读' }}</button><select v-model="accent" aria-label="发音口音"><option value="en-US">美音</option><option value="en-GB">英音</option></select><label><input v-model="slow" type="checkbox" /> 慢速</label><small v-if="error" role="status">{{ error }}</small></div></template>
<style scoped>.word-pronunciation{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:10px 0;font-size:12px;color:var(--muted)}select{border:0;background:transparent;color:var(--green);font:inherit;padding:4px;cursor:pointer}label{display:flex;align-items:center;gap:5px;font-size:12px}small{flex-basis:100%;color:var(--muted)}</style>
