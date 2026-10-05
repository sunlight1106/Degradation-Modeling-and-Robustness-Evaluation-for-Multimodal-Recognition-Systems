<script setup lang="ts">
import { computed, inject } from 'vue'
import { createSymbolHighlight, symbolHighlightKey } from '@/lib/symbolHighlight'
const props = withDefaults(defineProps<{ code: string; id: string; start?: number; targets?: Record<string, string> }>(), { start: 1 })
const { activeTerm, enter, leave, focus, blur, pointerDown } = inject(symbolHighlightKey, null) ?? createSymbolHighlight()
const keywords = new Set(['const','let','var','return','if','else','await','async','import','from','public','class','new','true','false','null','void'])
const lines = computed(() => props.code.split('\n').map(line => (line.match(/("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\/\/[^\n]*|#[^\n]*|\b\d+(?:\.\d+)?\b|[A-Za-z_$][\w$]*|\s+|.)/g) || [])))
function kind(token: string) { return keywords.has(token) ? 'keyword' : /^("|')/.test(token) ? 'string' : /^(#|\/\/)/.test(token) ? 'comment' : /^\d/.test(token) ? 'number' : /^[A-Za-z_$][\w$]*$/.test(token) ? 'identifier' : 'plain' }
const definitions = computed(() => { const result: Record<string, string> = {}; lines.value.forEach((line, index) => line.forEach(token => { if (kind(token) === 'identifier' && !result[token]) result[token] = `${props.id}-L${index + props.start}` })); return { ...result, ...props.targets } })
function follow(token: string) { requestAnimationFrame(() => document.getElementById(definitions.value[token])?.focus({ preventScroll: true })) }
</script>
<template><div class="code-listing" aria-label="带行号的代码示例"><div v-for="(tokens, index) in lines" :id="`${id}-L${index + start}`" :key="index" class="code-line" tabindex="-1"><a class="code-line-number" :href="`#${id}-L${index + start}`" :aria-label="`第 ${index + start} 行`">{{ index + start }}</a><code><template v-for="(token, i) in tokens" :key="i"><a v-if="kind(token) === 'identifier'" :href="`#${definitions[token]}`" class="code-symbol" :class="{ 'symbol-active': activeTerm === token }" :title="`定位 ${token} 的定义 / 首次出现`" @click="follow(token)" @mouseenter="enter(token)" @mouseleave="leave" @focus="focus($event, token)" @blur="blur" @pointerdown="pointerDown">{{ token }}</a><span v-else :class="`syntax-${kind(token)}`">{{ token }}</span></template></code></div></div></template>
