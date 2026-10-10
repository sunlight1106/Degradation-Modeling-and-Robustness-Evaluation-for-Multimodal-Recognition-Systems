<script setup lang="ts">
import {computed} from 'vue'
import {diffLines} from '@/lib/noteDiff'
const props=defineProps<{before:string;after:string}>(),lines=computed(()=>diffLines(props.before,props.after))
</script>
<template><div class="note-diff"><p v-if="before===after">正文没有变化。</p><p v-else>删除以红色标出，新增以绿色标出。相邻改动按区段展示，最多显示 800 行。</p><pre><span v-for="(line,i) in lines" :key="i" :class="line.kind"><small>{{line.oldLine??' '}}　{{line.newLine??' '}}</small>{{line.kind==='remove'?'−':line.kind==='add'?'+':' '}} {{line.text}}&#10;</span></pre></div></template>
<style scoped>pre{max-height:450px;overflow:auto;font:12px/1.8 ui-monospace,monospace;border:1px solid var(--line);white-space:pre-wrap;overflow-wrap:anywhere}pre>span{display:block;min-height:1.8em}small{display:inline-block;min-width:70px;color:var(--muted);user-select:none}.remove{background:#fbe9e7;color:#9c3535}.add{background:#e8f3ec;color:#235b3d}p{font-size:12px;color:var(--muted)}</style>
