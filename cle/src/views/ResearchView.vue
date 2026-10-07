<script setup lang="ts">
import { computed,onMounted,ref,watch } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { researchApi,type Hit,type Source } from '@/api/research'
import { personalApi } from '@/api/personal'
import { authStore } from '@/stores/auth'
import type { PersonalAiPreview,PersonalAiSetting } from '@/types/personal'
import StudyCards from '@/components/StudyCards.vue'
import KnowledgeCollections from '@/components/KnowledgeCollections.vue'
import BenchmarkPanel from '@/components/BenchmarkPanel.vue'
import RecoveryPanel from '@/components/RecoveryPanel.vue'
const route=useRoute(),router=useRouter(),tab=computed(()=>String(route.query.tab||'search'))
const tabs=[['search','资料搜索与问答'],['cards','复习卡'],['collections','资料表'],['evaluations','模型评测'],['trash','回收站与备份']]
const q=ref(String(route.query.q||'')),type=ref(String(route.query.type||'ALL')),tag=ref(''),since=ref(''),group=ref(''),page=ref(0),hasMore=ref(false)
const hits=ref<Hit[]>([]),selected=ref<Hit[]>([]),source=ref<Source|null>(null),error=ref(''),busy=ref(false),answerBusy=ref(false),question=ref(''),provider=ref(''),settings=ref<PersonalAiSetting[]>([]),preview=ref<PersonalAiPreview|null>(null),answer=ref(''),consent=ref(false)
let serial=0,sourceSerial=0
const kinds=[['ALL','全部资料'],['NOTE','笔记'],['ENTRY','知识卡'],['FILE','附件名称'],['RESULT','图片识别文字'],['TASK','实验结果'],['GROUP','群组资料']]
function resetAnswer(){preview.value=null;consent.value=false;answer.value=''}
watch([question,provider,selected],resetAnswer,{deep:true})
async function search(){const token=++serial;busy.value=true;error.value='';try{const params:Record<string,string>={q:q.value,type:type.value,tag:tag.value,page:String(page.value)};if(since.value)params.since=new Date(since.value).toISOString();if(group.value)params.group=group.value;const result=await researchApi.search(params);if(token===serial){hits.value=result.items;hasMore.value=result.hasMore}}catch(e){if(token===serial)error.value=(e as Error).message}finally{if(token===serial)busy.value=false}}
function toggle(h:Hit){const i=selected.value.findIndex(s=>s.kind===h.kind&&s.id===h.id);if(i>=0)selected.value.splice(i,1);else if(selected.value.length<8)selected.value.push(h)}
async function view(h:Pick<Hit,'kind'|'id'>){const token=++sourceSerial;error.value='';source.value=null;try{const result=await researchApi.source(h.kind,h.id);if(token===sourceSerial)source.value=result}catch(e){if(token===sourceSerial)error.value=(e as Error).message}}
async function ask(){answerBusy.value=true;error.value='';answer.value='';try{preview.value=await researchApi.preview(provider.value,question.value,selected.value);consent.value=false}catch(e){error.value=(e as Error).message}finally{answerBusy.value=false}}
async function execute(){if(!preview.value||!consent.value)return;answerBusy.value=true;error.value='';try{const result=await researchApi.answer(preview.value.previewToken);answer.value=result.result;preview.value=null}catch(e){error.value=(e as Error).message;preview.value=null}finally{answerBusy.value=false}}
async function capture(){if(!source.value)return;busy.value=true;try{const note=await researchApi.capture(source.value.kind,source.value.id);void router.push('/app/notes/'+note.id+'/edit')}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
async function initial(){await search();if(route.query.source)await view({kind:String(route.query.type||'NOTE'),id:String(route.query.source)});try{settings.value=(await personalApi.settings()).filter(s=>s.configured&&s.enabled);provider.value=settings.value[0]?.provider||''}catch{/* Configuration remains accessible from the settings link. */}}
watch(()=>route.query.source,()=>{if(route.query.source)void view({kind:String(route.query.type||'NOTE'),id:String(route.query.source)})})
onMounted(initial)
</script>
<template>
 <div class="research-space"><header class="research-intro"><p class="page-kicker">LEARNING WORKSPACE</p><h1>让资料成为自己的知识。</h1><p>检索、整理、复习与验证，都从这里继续。</p></header>
 <nav class="research-tabs" aria-label="学习中心"><RouterLink v-for="t in tabs" :key="t[0]" :to="{path:'/app/research',query:{tab:t[0]}}" :aria-current="tab===t[0]?'page':undefined">{{ t[1] }}</RouterLink></nav>
 <template v-if="tab==='search'"><form class="research-filters" @submit.prevent="page=0;search()"><input v-model="q" class="field-input" aria-label="搜索资料" placeholder="输入关键词，查找已有资料" maxlength="160"/><select v-model="type" class="field-input" aria-label="资料类型"><option v-for="k in kinds" :key="k[0]" :value="k[0]">{{ k[1] }}</option></select><input v-model="tag" class="field-input" aria-label="标签" placeholder="标签" maxlength="80"/><input v-model="since" type="date" class="field-input" aria-label="起始日期"/><button class="button button--dark" :disabled="busy">{{ busy?'搜索中…':'搜索' }}</button></form>
 <p v-if="error" role="alert" class="inline-alert inline-alert--error">{{ error }}</p>
 <div class="research-columns"><section><div class="research-section-title"><h2>搜索结果</h2><small>选择最多 8 份资料用于问答</small></div><p v-if="!hits.length&&!busy" class="research-empty">没有找到资料。试试更短的关键词或其他类型。</p>
 <article v-for="h in hits" :key="h.kind+h.id" class="search-result"><input type="checkbox" :checked="selected.some(s=>s.id===h.id&&s.kind===h.kind)" :disabled="answerBusy||selected.length>=8&&!selected.some(s=>s.id===h.id&&s.kind===h.kind)" :aria-label="'选择 '+h.title" @change="toggle(h)"/><div><small>{{ kinds.find(k=>k[0]===h.kind)?.[1] }} · {{ new Date(h.updatedAt).toLocaleDateString() }}</small><button class="result-title" @click="view(h)">{{ h.title }}</button><p>{{ h.excerpt }}</p></div></article>
 <div class="research-pagination"><button class="button button--ghost" :disabled="page===0||busy" @click="page--;search()">上一页</button><span>{{ page+1 }}</span><button class="button button--ghost" :disabled="!hasMore||busy" @click="page++;search()">下一页</button></div>
 <section v-if="source" class="source-reader"><div class="research-section-title"><h2>{{ source.title }}</h2><button class="button button--ghost" @click="source=null">收起</button></div><pre>{{ source.body }}</pre><div class="research-actions"><RouterLink v-if="!['RESULT','GROUP'].includes(source.kind)" :to="source.url">打开原始资料 →</RouterLink><button v-if="authStore.has('note:write')" class="button button--ghost" :disabled="busy" @click="capture">存为新笔记</button><RouterLink :to="{path:'/app/research',query:{tab:'cards',source:source.id,type:source.kind}}">制作复习卡 →</RouterLink></div></section>
 </section><aside class="research-answer"><h2>向自己的资料提问</h2><p class="research-hint">回答会标注来源编号。可以检索有访问权限的群组资料，再手动选择发送范围。</p><div class="source-chips"><button v-for="(s,i) in selected" :key="s.kind+s.id" :disabled="answerBusy" @click="toggle(s)">S{{ i+1 }} · {{ s.title }} ×</button></div><textarea v-model="question" class="field-input" :disabled="answerBusy" rows="4" maxlength="1000" placeholder="例如：这些资料中，哪种方法适合我的实验？" aria-label="问题"/><select v-model="provider" class="field-input" :disabled="answerBusy" aria-label="问答供应商"><option value="">选择个人 AI</option><option v-for="s in settings" :key="s.provider" :value="s.provider">{{ s.provider }} · {{ s.model }}</option></select><RouterLink v-if="!settings.length" to="/app/settings?section=ai">先配置个人 AI →</RouterLink><button class="button button--dark" :disabled="answerBusy||!provider||!selected.length||!question.trim()" @click="ask">预览发送内容</button>
 <section v-if="preview" class="answer-preview"><strong>{{ preview.provider }} · {{ preview.model }}</strong><pre>{{ preview.context }}</pre><label><input v-model="consent" type="checkbox" :disabled="answerBusy"/>确认发送这些资料，使用我的个人密钥调用</label><button class="button button--dark" :disabled="answerBusy||!consent" @click="execute">{{ answerBusy?'回答生成中…':'确认并提问' }}</button></section><section v-if="answer" class="answer-output"><h3>回答</h3><pre>{{ answer }}</pre><ol><li v-for="s in selected" :key="s.kind+s.id"><button class="result-title" @click="view(s)">{{ s.title }}</button></li></ol></section></aside></div></template>
 <StudyCards v-else-if="tab==='cards'"/><KnowledgeCollections v-else-if="tab==='collections'"/><BenchmarkPanel v-else-if="tab==='evaluations'"/><RecoveryPanel v-else-if="tab==='trash'"/>
 </div>
</template>
