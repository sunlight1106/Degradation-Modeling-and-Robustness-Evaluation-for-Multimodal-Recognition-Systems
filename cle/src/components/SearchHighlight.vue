<script setup lang="ts">
import { computed } from 'vue'
const props=defineProps<{text:string;query:string}>()
const parts=computed(()=>{
 const terms=[...new Set(props.query.trim().split(/\s+/).filter(Boolean))].slice(0,10).sort((a,b)=>b.length-a.length)
 if(!terms.length)return [{text:props.text,match:false}]
 const expression=new RegExp('('+terms.map(term=>term.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')).join('|')+')','ig')
 return props.text.split(expression).map((text,index)=>({text,match:index%2===1}))
})
</script>
<template><template v-for="(part,index) in parts" :key="index"><mark v-if="part.match">{{ part.text }}</mark><template v-else>{{ part.text }}</template></template></template>
