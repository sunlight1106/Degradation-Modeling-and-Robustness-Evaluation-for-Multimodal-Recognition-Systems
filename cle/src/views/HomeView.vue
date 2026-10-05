<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import DocRow from '@/components/DocRow.vue'
import CodeListing from '@/components/CodeListing.vue'
import { authStore } from '@/stores/auth'
onMounted(() => { void authStore.ensureUser() })
const enter = computed(() => authStore.state.user ? '/app/home' : '/login')
const chapters = [
  { id: 'recognition', title: '识别与实验', intro: '从原始输入到结构化输出，记录每一次实验。', links: [{ title: '新建识别实验', to: '/app/upload', text: '车牌、票据与音视频分析' }, { title: '模型目录', to: '/models', text: '查看模型能力与调用方式' }, { title: '优化对比', to: '/app/comparisons', text: '并列检查基线与优化结果' }] },
  { id: 'knowledge', title: '知识与记录', intro: '把研究过程整理为可检索、可继续的笔记。', links: [{ title: '知识库', to: '/app/knowledge', text: '主题索引与学习资料' }, { title: '我的笔记', to: '/app/notes', text: 'Markdown 编辑与分享' }, { title: '素材库', to: '/app/images', text: '管理实验中的图片与视频' }] },
  { id: 'reproduce', title: '结果与复现', intro: '保留输入、配置和输出，让结论有据可查。', links: [{ title: '调用日志', to: '/app/logs', text: '查询任务状态与运行记录' }, { title: '下载中心', to: '/app/downloads', text: '导出实验文件与结果' }, { title: '使用文档', to: '/docs', text: '部署说明与操作指南' }] },
]
</script>
<template><div class="lab-docs lab-home"><header class="lab-header"><nav aria-label="面包屑"><a href="#introduction">HOME</a><span>›</span></nav><div class="lab-header-links"><RouterLink to="/docs">使用文档</RouterLink><RouterLink to="/models">模型目录</RouterLink><RouterLink :to="enter">{{ authStore.state.user ? '工作台' : '登录' }} ↗</RouterLink></div></header><main>
<DocRow id="introduction" title="多模态识别与鲁棒性研究空间" intro><p>连接模型、组织知识，理解每一次识别的结果。</p><p>从原始输入开始，对照退化条件下的模型表现，记录优化过程。将数据、实验和笔记放在同一个工作空间里。</p><p><RouterLink :to="enter">进入工作台</RouterLink> · <RouterLink to="/docs">阅读使用文档</RouterLink></p><template #detail><div class="lab-index-note"><span>PERSONAL PLATFORM</span><p>研究、记录与复现。</p><p>选择一个主题，继续你的工作。</p></div></template></DocRow>
<DocRow v-for="chapter in chapters" :key="chapter.id" :id="chapter.id" :title="chapter.title"><p>{{ chapter.intro }}</p><ul class="lab-index-list"><li v-for="item in chapter.links" :key="item.to"><RouterLink :to="item.to">{{ item.title }}</RouterLink><p>{{ item.text }}</p></li></ul><template #detail><p class="lab-margin-note">{{ chapter.id.toUpperCase() }}</p><p class="lab-section-description">{{ chapter.id === 'recognition' ? '固定输入与模型版本，让基线与优化结果可对照。' : chapter.id === 'knowledge' ? '按主题整理知识，用笔记连接素材与实验。' : '保留配置、输出和观察，为下一次实验留下依据。' }}</p></template></DocRow>
<DocRow id="method" title="同一输入，两条对照路径"><p>分别记录基线和优化结果。置信度变化只是一条线索，真实标注才是判断准确率的依据。</p><RouterLink to="/docs#reading-guide">逐行阅读实验配置 →</RouterLink><template #detail><CodeListing id="home-method" code="# 实验流程示意
input → baseline
input → preprocessing → optimized

# 比较输出，保留记录
compare(baseline, optimized)" /></template></DocRow></main><footer class="lab-footer"><span>Personal Platform</span><a href="#introduction">返回顶部 ↑</a></footer></div></template>
