<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'

const notebookUrl = 'https://colab.research.google.com/github/labmlai/annotated_deep_learning_paper_implementations/blob/master/labml_nn/lora/experiment.ipynb#scrollTo=c5ed37230628ee76'
</script>

<template>
  <aside class="lora-colab-card" aria-label="LoRA 微调在线实验">
    <div class="lora-colab-content"><span class="lora-colab-label"><AppIcon name="terminal" :size="16" /> 在线实验 · GPT-2 / LoRA</span><h4>动手学习 LoRA 微调</h4><p>跟随交互式笔记本，从安装依赖、初始化模型到运行训练，逐步理解微调流程。</p></div>
    <div class="lora-colab-actions"><a :href="notebookUrl" target="_blank" rel="noopener noreferrer" class="lora-colab-button">打开 Colab 实验 <span aria-hidden="true">↗</span><span class="lora-colab-sr">（新标签页）</span></a><small>训练在 Colab 中运行</small></div>
  </aside>
</template>

<style scoped>
.lora-colab-card{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:20px 28px;padding:24px 26px;margin:20px 0 28px;border:1px solid var(--line);border-left:4px solid var(--green);border-radius:5px;background:var(--green-soft);color:var(--ink)}
.lora-colab-content{flex:1 1 260px;min-width:0}.lora-colab-label{display:flex;align-items:center;gap:8px;color:var(--green);font-size:11px;letter-spacing:.05em}.lora-colab-content h4{font-size:20px;font-weight:550;line-height:1.5;margin:10px 0 8px}.lora-colab-content p{font-size:13px;line-height:1.85;color:var(--muted);margin:0;max-width:620px}
.lora-colab-actions{display:flex;flex-direction:column;align-items:center;gap:9px}.lora-colab-card .lora-colab-button{display:inline-flex;align-items:center;justify-content:space-between;gap:24px;padding:12px 18px;border:1px solid var(--green);border-radius:4px;background:var(--green);color:var(--paper);font-size:14px;font-weight:500;text-decoration:none;white-space:nowrap;transition:filter .15s,box-shadow .15s}.lora-colab-button:hover{filter:brightness(.92);box-shadow:0 3px 10px #2030401a}.lora-colab-button:focus-visible{outline:2px solid var(--green);outline-offset:4px}.lora-colab-actions small{font-size:11px;color:var(--muted)}.lora-colab-sr{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap}
@media(prefers-reduced-motion:reduce){.lora-colab-card .lora-colab-button{transition:none}}
</style>
