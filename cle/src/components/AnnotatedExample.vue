<script setup lang="ts">
import { provide } from 'vue'
import { createSymbolHighlight, symbolHighlightKey } from '@/lib/symbolHighlight'
import CodeListing from './CodeListing.vue'
import DocRow from './DocRow.vue'
const highlight = createSymbolHighlight()
provide(symbolHighlightKey, highlight)
const { activeTerm, enter, leave, focus, blur, pointerDown } = highlight
const rows = [
  { id: 'input', title: '选择实验输入', note: 'fileId 对应已经上传的素材；modelId 对应模型目录中的可用模型。这里的数字仅作示例，请替换成自己的 ID。', code: ['const fileId = 12;', 'const modelId = 3;'], line: 1 },
  { id: 'options', title: '配置对照路径', note: 'enhancementEnabled 控制是否启用预处理。固定 fileId 和 modelId，分别比较开启与关闭后的识别输出。', code: ['const request = {', '  fileId, modelId,', '  taskType: "LICENSE_PLATE",', '  enhancementEnabled: true', '};'], line: 4 },
  { id: 'record', title: '保留实验配置', note: 'request 汇总此次调用的输入和参数。保存配置有助于复查实验；下方仅展示序列化，不会发起付费模型调用。', code: ['const config = JSON.stringify(request, null, 2);', 'console.log(config);'], line: 10 },
]
const targets: Record<string, string> = { fileId: 'input-code-L1', modelId: 'input-code-L2', enhancementEnabled: 'options-code-L7', request: 'options-code-L4', config: 'record-code-L10' }
function parts(text: string) { return text.split(/(fileId|modelId|enhancementEnabled|request|config)/g) }
function focusTerm(term: string) { requestAnimationFrame(() => document.getElementById(targets[term])?.focus({ preventScroll: true })) }
</script>
<template><div class="annotated-reader"><DocRow v-for="row in rows" :key="row.id" :id="`guide-${row.id}`" :title="row.title"><p><template v-for="(part, i) in parts(row.note)" :key="i"><a v-if="targets[part]" class="prose-symbol" :class="{ 'symbol-active': activeTerm === part }" :href="`#${targets[part]}`" :title="`查看 ${part} 的定义`" @click="focusTerm(part)" @mouseenter="enter(part)" @mouseleave="leave" @focus="focus($event, part)" @blur="blur" @pointerdown="pointerDown">{{ part }}</a><template v-else>{{ part }}</template></template></p><a v-if="row.id === 'input'" href="/models">模型目录</a><template #detail><CodeListing :id="`${row.id}-code`" :code="row.code.join('\n')" :start="row.line" :targets="targets" /></template></DocRow></div></template>
