<script setup lang="ts">
import { ref } from 'vue'
import PersonalAiProviders from './PersonalAiProviders.vue'
import PersonalAiMemory from './PersonalAiMemory.vue'
import PersonalAiTraining from './PersonalAiTraining.vue'
const tab = ref('providers')
const tabs = [{id:'providers', label:'模型服务'}, {id:'memories', label:'共享记忆'}, {id:'training', label:'本地训练'}]
</script>
<template>
  <div class="personal-ai-workspace">
    <nav class="ai-setting-tabs" aria-label="个人 AI 设置"><button v-for="item in tabs" :key="item.id" :aria-pressed="tab === item.id" :class="{active: tab === item.id}" @click="tab = item.id">{{ item.label }}</button></nav>
    <PersonalAiProviders v-if="tab === 'providers'" />
    <div v-else class="panel settings-card"><PersonalAiMemory v-if="tab === 'memories'" /><PersonalAiTraining v-else /></div>
  </div>
</template>
<style>
.ai-setting-tabs{display:flex;gap:30px;border-bottom:1px solid var(--line);margin-bottom:28px}.ai-setting-tabs button{padding:12px 0;color:var(--muted,#758392);background:none;border:0;border-bottom:2px solid transparent;cursor:pointer;font:inherit}.ai-setting-tabs button:hover,.ai-setting-tabs button.active{color:var(--ink);border-bottom-color:var(--green)}.ai-section-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:24px;margin-bottom:20px}.ai-section-heading h3{margin:0 0 10px;font-size:23px}.ai-section-heading p{margin:0;line-height:1.8;max-width:620px;color:var(--muted,#758392)}.ai-section-heading>span{font-size:12px;white-space:nowrap;color:var(--green);padding-top:8px}
</style>
