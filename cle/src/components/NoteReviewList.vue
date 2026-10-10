<script setup lang="ts">
import { ref,onMounted,onBeforeUnmount } from 'vue'
import { workflowApi,type DueNote } from '@/api/workflows'
import { authStore } from '@/stores/auth'
const rows=ref<DueNote[]>([]),error=ref('');let active=true
onBeforeUnmount(()=>active=false)
onMounted(async()=>{if(!authStore.has('note:read'))return;try{const value=await workflowApi.due();if(active)rows.value=value}catch(e){if(active)error.value=(e as Error).message}})
</script>
<template><details v-if="rows.length||error" class="note-review"><summary>待复习的笔记 · {{ rows.length }}</summary><p v-if="error" role="alert">{{ error }}</p><RouterLink v-for="note in rows" :key="note.id" :to="`/app/notes/${note.id}/edit`"><span>{{ note.title }}</span><time>{{ note.date }}</time></RouterLink></details></template>
<style scoped>.note-review{border-top:1px solid var(--line);border-bottom:1px solid var(--line);padding:18px 0}summary{cursor:pointer;color:var(--green);font-size:13px}.note-review a{display:flex;justify-content:space-between;padding:12px 0;text-decoration:none;font-size:13px;border-bottom:1px solid var(--line)}time,p{color:var(--muted);font-size:12px}</style>
