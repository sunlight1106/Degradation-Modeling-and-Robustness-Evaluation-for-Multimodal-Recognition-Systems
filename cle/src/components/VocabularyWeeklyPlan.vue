<script setup lang="ts">
import { computed,onMounted,onBeforeUnmount,ref } from 'vue'
import { workflowApi,type WorkflowPreferences } from '@/api/workflows'
import type { VocabularyDashboard } from '@/api/vocabulary'
const props=defineProps<{dashboard:VocabularyDashboard}>(),plan=ref<WorkflowPreferences>();let active=true
onBeforeUnmount(()=>{active=false})
onMounted(async()=>{try{const p=await workflowApi.preferences();if(active)plan.value=p}catch{ /* The study session remains usable while preferences are unavailable. */ }})
const date=computed(()=>props.dashboard.today.date||new Intl.DateTimeFormat('sv',{timeZone:props.dashboard.settings.zoneId||'UTC'}).format(new Date()))
const monday=computed(()=>{const d=new Date(`${date.value}T12:00:00Z`);d.setUTCDate(d.getUTCDate()-((d.getUTCDay()+6)%7));return d.toISOString().slice(0,10)})
const answers=computed(()=>props.dashboard.history.filter(d=>d.date&&d.date>=monday.value&&d.date<=date.value).reduce((sum,d)=>sum+d.answers,0))
const target=computed(()=>plan.value?.weeklyTarget||70),progress=computed(()=>Math.min(100,answers.value/target.value*100))
</script>
<template><section class="weekly-plan" aria-label="本周学习计划"><div><strong>本周练习</strong><span>{{answers}} / {{target}} 次</span><a href="/app/settings?section=workflow">调整计划 →</a></div><progress :value="progress" max="100" :aria-label="`本周已完成 ${answers} 次练习`"/><p>周一至周日统计答题次数；掌握程度另按独立回忆和复习表现记录。</p></section></template>
<style scoped>.weekly-plan{margin:24px 0;padding:20px 0;border-block:1px solid var(--line)}.weekly-plan div{display:flex;gap:16px;align-items:baseline}.weekly-plan a{margin-left:auto;text-decoration:none}.weekly-plan progress{display:block;width:100%;height:6px;margin-top:16px;accent-color:var(--green)}.weekly-plan p{margin-top:10px;color:var(--muted);font-size:12px}</style>
