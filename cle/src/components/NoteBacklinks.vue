<script setup lang="ts">
import {onMounted,onBeforeUnmount,ref} from 'vue'
import {RouterLink} from 'vue-router'
import {request} from '@/api/client'
const props=defineProps<{id:string}>(),rows=ref<{id:string;title:string}[]>([]),error=ref('');let active=true
onBeforeUnmount(()=>{active=false});onMounted(async()=>{try{const r=await request<typeof rows.value>(`/notes/${props.id}/backlinks`);if(active)rows.value=r}catch(e){if(active)error.value=(e as Error).message}})
</script>
<template><details class="backlinks"><summary>链接到本页 · {{rows.length}}</summary><p v-if="error" role="alert">{{error}}</p><p v-if="!rows.length">在其他笔记中插入本页链接后，会显示在这里。</p><RouterLink v-for="r in rows" :key="r.id" :to="`/app/notes/${r.id}/edit`">{{r.title}} →</RouterLink></details></template>
<style scoped>.backlinks{border-top:1px solid var(--line);padding:16px 0;font-size:13px}summary{cursor:pointer;color:var(--green)}a{display:block;text-decoration:none;padding:8px 0}p{color:var(--muted)}</style>
