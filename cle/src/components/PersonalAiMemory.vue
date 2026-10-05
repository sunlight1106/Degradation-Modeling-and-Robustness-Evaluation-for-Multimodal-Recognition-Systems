<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { personalApi } from '@/api/personal'
import type { PersonalAiMemory } from '@/types/personal'

const memories = ref<PersonalAiMemory[]>([]), error = ref(''), busy = ref(false), loading = ref(true)
const editing = ref<PersonalAiMemory | null>(null)
const form = reactive({ title: '', body: '', enabled: true })
const enabledCount = computed(() => memories.value.filter(item => item.enabled).length)
const used = computed(() => memories.value.filter(item => item.enabled).reduce((sum, item) => sum + item.title.length + item.body.length, 0))
let active = true
function reset() { editing.value = null; form.title = ''; form.body = ''; form.enabled = true }
function edit(item: PersonalAiMemory) { editing.value = item; Object.assign(form, item); error.value = '' }
async function load() {
  loading.value = true
  try { const saved = await personalApi.memories(); if (active) memories.value = saved }
  catch (e) { if (active) error.value = e instanceof Error ? e.message : '记忆加载失败' }
  finally { if (active) loading.value = false }
}
async function save() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const saved = await personalApi.saveMemory(editing.value?.id, { title: form.title, body: form.body, enabled: form.enabled, revision: editing.value?.revision })
    if (!active) return
    memories.value = [...memories.value.filter(item => item.id !== saved.id), saved]; reset()
  } catch (e) { if (active) error.value = e instanceof Error ? e.message : '保存失败' }
  finally { if (active) busy.value = false }
}
async function change(item: PersonalAiMemory, remove = false) {
  if (busy.value || (remove && !window.confirm(`删除记忆“${item.title}”？后续请求将不再使用它。`))) return
  busy.value = true; error.value = ''
  try {
    if (remove) await personalApi.deleteMemory(item.id, item.revision)
    else await personalApi.saveMemory(item.id, { ...item, enabled: !item.enabled })
    if (!active) return
    if (editing.value?.id === item.id) reset()
    await load()
  } catch (e) { if (active) error.value = e instanceof Error ? e.message : '操作失败' }
  finally { if (active) busy.value = false }
}
onMounted(load)
onBeforeUnmount(() => { active = false; reset(); memories.value = [] })
</script>
<template>
  <section class="ai-memory">
    <header class="ai-section-heading"><div><h3>让 AI 记住你的偏好</h3><p>写一次，在你的不同 AI 服务间共用。只有你能查看、编辑和删除这些记忆。</p></div><span>{{ enabledCount }} 条启用</span></header>
    <p class="field-hint">已启用的记忆会随笔记 AI 和图片理解请求发送给你选定的供应商；每次发送前都能预览。停用后仍会保留在这里。</p>
    <p v-if="error" class="inline-alert inline-alert--error" role="alert">{{ error }} <button class="table-action" :disabled="busy" @click="load">刷新列表</button></p>
    <div class="ai-memory-layout">
      <form class="ai-memory-editor" @submit.prevent="save">
        <h4>{{ editing ? '编辑记忆' : '添加一条记忆' }}</h4>
        <label class="field-label">标题<input v-model="form.title" class="field-input" maxlength="100" required :disabled="busy" placeholder="例如：我的学习方式" /></label>
        <label class="field-label">内容<textarea v-model="form.body" class="field-input" rows="6" maxlength="1000" required :disabled="busy" placeholder="我正在学习 Java。请用中文解释，先给简短例子，再讲原理。" /></label>
        <small>{{ form.body.length }} / 1000 字</small>
        <label class="settings-check"><input v-model="form.enabled" type="checkbox" :disabled="busy" /> 在我的 AI 请求中使用</label>
        <div class="settings-button-row"><button class="button button--dark" :disabled="busy || loading">{{ busy ? '处理中…' : editing ? '保存更改' : '添加记忆' }}</button><button v-if="editing" type="button" class="button button--ghost" :disabled="busy" @click="reset">取消编辑</button></div>
      </form>
      <div class="ai-memory-list" :aria-busy="loading"><div class="ai-list-heading"><h4>我的记忆</h4><small>{{ used }} / 6000 字已启用</small></div>
        <p v-if="loading" role="status">正在加载…</p><p v-else-if="!memories.length" class="settings-empty">还没有记忆。可以从表达风格、学习目标或常用背景开始。</p>
        <article v-for="item in memories" :key="item.id" class="ai-memory-item" :class="{ 'is-disabled': !item.enabled }"><div class="ai-list-heading"><strong>{{ item.title }}</strong><small>{{ item.enabled ? '启用' : '停用' }}</small></div><p>{{ item.body }}</p><div class="ai-memory-actions"><button class="table-action" :disabled="busy" @click="edit(item)">编辑</button><button class="table-action" :disabled="busy" @click="change(item)">{{ item.enabled ? '停用' : '启用' }}</button><button class="table-action note-danger" :disabled="busy" @click="change(item, true)">删除</button></div></article>
      </div>
    </div>
  </section>
</template>
<style scoped>
.ai-memory-layout{display:grid;grid-template-columns:minmax(240px,.8fr) minmax(280px,1.2fr);gap:40px;margin-top:28px}.ai-memory-editor{display:flex;flex-direction:column;gap:16px;align-self:start}.ai-memory-editor h4,.ai-list-heading h4{margin:0}.ai-memory-editor textarea{resize:vertical;min-height:150px}.ai-list-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.ai-memory-list{border-left:1px solid var(--line);padding-left:30px}.ai-memory-item{padding:22px 0;border-bottom:1px solid var(--line)}.ai-memory-item p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8;margin:10px 0}.ai-memory-actions{display:flex;gap:20px}.is-disabled strong{color:var(--muted,#72818e)}small{color:var(--muted,#72818e)}
</style>
