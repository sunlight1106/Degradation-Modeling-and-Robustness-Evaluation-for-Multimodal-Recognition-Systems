<script setup lang="ts">
import { onMounted,onBeforeUnmount,ref } from 'vue'
import { researchApi,type SearchFilters,type SavedSearch } from '@/api/research'
import { createRequestGuard } from '@/lib/requestGuard'
const props=defineProps<{filters:SearchFilters}>(),emit=defineEmits<{apply:[filters:SearchFilters]}>()
const rows=ref<SavedSearch[]>([]),name=ref(''),busy=ref(false),error=ref(''),guard=createRequestGuard()
const panel=ref<HTMLDetailsElement|null>(null)
async function load(){const request=guard.start();try{const result=await researchApi.savedSearches(request.signal);if(request.current())rows.value=result}catch(e){if(request.current())error.value=(e as Error).message}}
async function save(){if(busy.value||!name.value.trim())return;const request=guard.start();busy.value=true;error.value='';try{const row=await researchApi.saveSearch(name.value.trim(),props.filters);if(request.current()){rows.value=[row,...rows.value.filter(r=>r.id!==row.id)];name.value='';panel.value?.removeAttribute('open')}}catch(e){if(request.current())error.value=(e as Error).message}finally{if(request.current())busy.value=false}}
async function remove(row:SavedSearch){if(busy.value)return;const request=guard.start();busy.value=true;error.value='';try{await researchApi.removeSearch(row.id);if(request.current())rows.value=rows.value.filter(r=>r.id!==row.id)}catch(e){if(request.current())error.value=(e as Error).message}finally{if(request.current())busy.value=false}}
onMounted(load);onBeforeUnmount(()=>guard.cancel())
</script>
<template>
 <section class="saved-searches" aria-label="常用搜索"><div class="saved-search-row"><span>常用搜索</span><span v-if="!rows.length" class="research-hint">把常查的关键词与筛选条件保存在这里。</span><div v-for="row in rows" :key="row.id" class="saved-search-chip"><button :disabled="busy" @click="emit('apply',row.filters)">{{ row.name }}</button><button :disabled="busy" :aria-label="'删除常用搜索 '+row.name" @click="remove(row)">×</button></div><details ref="panel" class="save-search-form"><summary>＋ 保存当前搜索</summary><form @submit.prevent="save"><label>搜索名称<input v-model="name" class="field-input" maxlength="80" placeholder="例如：上周的实验笔记"/></label><button class="button button--dark" :disabled="busy||!name.trim()">保存</button></form></details></div><p v-if="error" role="alert">{{ error }}</p></section>
</template>
