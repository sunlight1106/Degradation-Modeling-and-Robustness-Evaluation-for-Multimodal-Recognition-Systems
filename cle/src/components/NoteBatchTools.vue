<script setup lang="ts">
import { ref, onBeforeUnmount, watch } from 'vue'
import type { NoteSummaryView } from '@/types/api'
import { request } from '@/api/client'
const props = defineProps<{ selected: NoteSummaryView[] }>()
const emit = defineEmits<{ changed: [count: number]; clear: []; busy: [value: boolean] }>()
const action = ref('MOVE'), value = ref(''), reviewing = ref(false), busy = ref(false), error = ref('')
let active = true
watch(() => props.selected.map(n => n.id).join(','), () => { reviewing.value = false; error.value = '' })
watch([action,value],()=>{reviewing.value=false})
onBeforeUnmount(() => { active = false; emit('busy',false) })
async function apply() {
  if (busy.value || !props.selected.length) return
  busy.value = true; emit('busy', true); error.value = ''
  try {
    const count = await request<number>('/notes/batch', { method: 'POST', body: JSON.stringify({ notes: props.selected.map(n => ({ id: n.id, revision: n.revision })), action: action.value, library: action.value === 'MOVE' ? value.value.trim() : undefined, tags: action.value === 'TAGS' ? value.value.trim() : undefined }) })
    if (active) { reviewing.value = false; emit('changed', count) }
  } catch (reason) { if (active) error.value = (reason as Error).message }
  finally { if (active) { busy.value = false; emit('busy', false) } }
}
</script>
<template>
  <section v-if="selected.length" class="note-batch" aria-label="批量整理笔记">
    <div class="batch-actions"><strong>已选 {{ selected.length }} 篇</strong><select v-model="action" class="field-input" aria-label="整理操作" :disabled="busy" @change="reviewing = false"><option value="MOVE">移到学习库</option><option value="TAGS">添加标签</option><option value="ARCHIVE">归档</option><option value="ACTIVE">设为定稿</option><option value="DRAFT">设为草稿</option><option value="TRASH">移入回收站</option></select><input v-if="action === 'MOVE' || action === 'TAGS'" v-model="value" class="field-input" :aria-label="action === 'MOVE' ? '目标学习库' : '批量添加标签'" :maxlength="action === 'MOVE' ? 40 : 500" :placeholder="action === 'MOVE' ? '输入学习库名称' : '多个标签用逗号分隔'" :disabled="busy" @input="reviewing = false" /><button class="button button--ghost" :disabled="busy || (['MOVE','TAGS'].includes(action) && !value.trim())" @click="reviewing = true">查看并确认</button><button class="table-action" :disabled="busy" @click="emit('clear')">取消选择</button></div>
    <div v-if="reviewing" class="batch-review"><p>{{ action === 'TRASH' ? '这些笔记会移入回收站，可以恢复。' : '将修改选中笔记的分类、标签或状态，正文保持当前内容。' }}</p><ul><li v-for="note in selected.slice(0,6)" :key="note.id">{{ note.title }}</li></ul><p v-if="selected.length > 6">另有 {{ selected.length - 6 }} 篇。</p><button class="button button--dark" :disabled="busy" @click="apply">{{ busy ? '正在整理…' : `确认处理 ${selected.length} 篇` }}</button></div>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
  </section>
</template>
<style scoped>.note-batch{padding:18px;border:1px solid var(--line);margin:18px 0;background:var(--paper)}.batch-actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.field-input{width:170px}.batch-actions input{flex:1;min-width:180px}.batch-review{border-top:1px solid var(--line);margin-top:18px;padding-top:12px;font-size:13px}p{color:var(--muted);line-height:1.8}ul{padding-left:20px}strong{font-size:13px}</style>
