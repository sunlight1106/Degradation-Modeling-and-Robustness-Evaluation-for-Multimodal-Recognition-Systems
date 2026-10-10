<script setup lang="ts">
import {ref,watch,onMounted,onBeforeUnmount} from 'vue'
import {RouterLink,useRouter} from 'vue-router'
import {safetyApi,labels,activePenalty,type Penalty} from '@/api/moderation'
const active=ref<Penalty[]>([]),router=useRouter();let alive=true,timer:ReturnType<typeof setInterval>|undefined
watch(()=>router.currentRoute.value.path,p=>{if(p!=='/app/safety'&&active.value.some(x=>x.kind==='BAN'&&activePenalty(x)))void router.replace('/app/safety')})
async function load(){if(document.hidden)return;try{const d=await safetyApi.mine();if(alive){active.value=d.penalties.filter(activePenalty);if(active.value.some(p=>p.kind==='BAN')&&router.currentRoute.value.path!=='/app/safety')void router.replace('/app/safety')}}catch{/* Existing session and request handlers handle connection failures. */}}
onMounted(()=>{void load();timer=setInterval(load,60000);window.addEventListener('pkb:live-update',load)})
onBeforeUnmount(()=>{alive=false;clearInterval(timer);window.removeEventListener('pkb:live-update',load)})
</script>
<template><div v-if="active.length" class="safety-banner" role="status"><span>账户限制：{{[...new Set(active.map(p=>labels[p.kind]))].join('、')}}</span><RouterLink to="/app/safety">查看原因与申诉 →</RouterLink></div></template>
<style scoped>.safety-banner{display:flex;align-items:center;justify-content:space-between;gap:20px;background:var(--code-paper);border-left:3px solid #b55530;padding:12px 18px;margin-bottom:24px;font-size:13px;color:var(--ink)}a{color:var(--green);text-decoration:none}</style>
