<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import DocRow from '@/components/DocRow.vue'
import AnnotatedExample from '@/components/AnnotatedExample.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'

const copied = ref(false)
const activeSection = ref('quickstart')
const search = ref('')
const searchInput = ref<HTMLInputElement | null>(null)
let observer: IntersectionObserver | null = null
let scrollFrame = 0
let copyTimer = 0
let mounted = true

const docLinks = [
  { id: 'quickstart', label: '快速开始', keywords: '登录 注册 first run' },
  { id: 'requirements', label: '启动与部署', keywords: 'Docker localhost 4173 环境' },
  { id: 'model-contract', label: '个人 AI 配置', keywords: 'BYOK key 密钥 供应商 模型 remote' },
  { id: 'ai-memory', label: '个人 AI 共享记忆', keywords: 'memory 偏好 修改 删除 隔离' },
  { id: 'local-training', label: '本地 PyTorch 训练', keywords: 'CPU 分类 训练 JSON 标注 模型 下载' },
  { id: 'first-request', label: '发送预览与确认', keywords: 'API JSON preview execute 取消' },
  { id: 'quality-route', label: '图片内容理解与识别', keywords: 'image recognition 视频 5 MiB' },
  { id: 'code-notes', label: '代码块与 AI 注释', keywords: 'Java Python main 代码 语法高亮 变量 注释 父子页面 Notion' },
  { id: 'knowledge-notes', label: '学习库与在线笔记', keywords: 'LOCAL_RULES 本地规则 知识库 英语 计算机 HTML PDF 导出 24000 24,000' },
  { id: 'online-platform', label: '多人平台与自动同步', keywords: '云端 联系人 好友 聊天 自动保存 公网 HTTPS 服务器 同步 冲突' },
  { id: 'group-collaboration', label: '群组与站内信', keywords: 'group 群主 成员 管理员 回复 附件 分享 资料 私信' },
  { id: 'account-switch', label: '切换账号与登录管理', keywords: '切换用户 多账号 密码 登录设备 会话 退出 名称 记住' },
  { id: 'permissions', label: '账户与隐私设置', keywords: '资料 密码 会话 导出 权限 外观' },
  { id: 'analysis-output', label: '使用记录与费用', keywords: 'usage token 账单 成本' },
  { id: 'vocabulary', label: '背单词与私有词书', keywords: '60 学习 复习 时区 导入' },
  { id: 'architecture', label: '服务与演示边界', keywords: 'demo 视频 worker 架构' },
  { id: 'training-data', label: '数据导出与训练边界', keywords: 'dataset 微调' },
  { id: 'acceptance', label: '首次验收清单', keywords: '测试 检查 acceptance' },
  { id: 'errors', label: '常见问题', keywords: '报错 失败 排查 error' },
]
const searchMatches = computed(() => !search.value.trim() ? [] : docLinks.filter(item =>
  `${item.label} ${item.keywords}`.toLowerCase().includes(search.value.trim().toLowerCase())))

const examples = [
  {
    id: 'text', label: '文本预览 JSON', endpoint: 'POST /api/v1/personal-ai/preview',
    description: '先在设置中保存并启用自己的配置。OPENAI 仅为供应商字段示例，不是默认模型；实际模型 ID 来自你的个人配置。',
    body: { provider: 'OPENAI', action: 'summarize', title: '无敏感信息的练习笔记', body: '这是一段用于检查预览的示例文本。尚未验证任何实验结论。', selectedTaskIds: [] },
  },
  {
    id: 'image', label: '图片预览 JSON', endpoint: 'POST /api/v1/personal-ai/recognition/preview',
    description: 'fileId 必须替换为自己上传、已通过扫描且不超过 5 MiB 的图片 ID；taskType 可选 IMAGE_UNDERSTANDING、RECEIPT 或 LICENSE_PLATE；question 可填写图片问题。',
    body: { provider: 'OPENAI', fileId: '<自己的已扫描图片 ID>', taskType: 'RECEIPT' },
  },
  {
    id: 'execute', label: '确认执行 JSON', endpoint: 'POST /api/v1/personal-ai/execute',
    description: '仅在核对完整预览并同意发送后使用。图片执行请改用 POST /api/v1/personal-ai/recognition/execute；两种令牌不能混用。远程开关关闭时会拒绝执行。',
    body: { previewToken: '<本次预览返回的一次性令牌>', confirmed: true },
  },
]
const selectedExample = ref('text')
const activeExample = computed(() => examples.find(item => item.id === selectedExample.value) || examples[0]!)
const activeCode = computed(() => JSON.stringify(activeExample.value.body, null, 2))

function openFirstSearch() {
  const first = searchMatches.value[0]
  if (!first) return
  document.getElementById(first.id)?.scrollIntoView({ behavior: 'smooth' })
  search.value = ''
}

function keyboard(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault(); searchInput.value?.closest('details')?.setAttribute('open', ''); searchInput.value?.focus()
  }
}

function syncActiveSection() {
  if (scrollFrame) return
  scrollFrame = window.requestAnimationFrame(() => {
    scrollFrame = 0
    const targetLine = window.innerHeight * 0.28
    const sections = docLinks
      .map(item => ({ id: item.id, node: document.getElementById(item.id) }))
      .filter((item): item is { id: string; node: HTMLElement } => Boolean(item.node))
    const passed = sections.filter(item => item.node.getBoundingClientRect().top <= targetLine)
    activeSection.value = (passed.at(-1) || sections[0])?.id || 'quickstart'
  })
}

onMounted(async () => {
  await authStore.ensureUser()
  await nextTick()
  if (!mounted) return
  if (typeof IntersectionObserver !== 'undefined') {
    observer = new IntersectionObserver(entries => {
      const visible = entries.filter(entry => entry.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)
      if (visible[0]?.target.id) activeSection.value = visible[0].target.id
    }, { rootMargin: '-18% 0px -66% 0px', threshold: [0, 0.1, 0.5] })
    docLinks.forEach(item => { const node = document.getElementById(item.id); if (node) observer?.observe(node) })
  }
  window.addEventListener('keydown', keyboard)
  window.addEventListener('scroll', syncActiveSection, { passive: true })
  syncActiveSection()
})
onBeforeUnmount(() => {
  mounted = false
  observer?.disconnect()
  window.removeEventListener('keydown', keyboard)
  window.removeEventListener('scroll', syncActiveSection)
  if (scrollFrame) window.cancelAnimationFrame(scrollFrame)
  if (copyTimer) window.clearTimeout(copyTimer)
})

async function copyCode() {
  try {
    await navigator.clipboard.writeText(activeCode.value)
    if (!mounted) return
    copied.value = true
    toastStore.success('示例 JSON 已复制')
    if (copyTimer) window.clearTimeout(copyTimer)
    copyTimer = window.setTimeout(() => { copied.value = false }, 1600)
  } catch { if (mounted) toastStore.error('复制失败，请手动选择示例 JSON') }
}
</script>

<template><div class="lab-docs guide-page">
  <header class="lab-header">
    <nav aria-label="当前位置"><RouterLink to="/">HOME</RouterLink><span>/</span><span>GUIDE</span></nav>
    <div class="lab-header-links"><RouterLink to="/app/home">工作台</RouterLink><RouterLink to="/app/upload">实验台</RouterLink><a href="/swagger-ui.html" target="_blank" rel="noreferrer">API Reference</a></div>
    <details class="lab-contents"><summary>查找与目录</summary><div class="guide-directory"><label class="guide-search"><input ref="searchInput" v-model="search" aria-label="搜索使用文档" placeholder="搜索操作或问题 · Ctrl K" @keydown.enter="openFirstSearch" /></label><div v-if="searchMatches.length" class="docs-search-results" aria-label="文档搜索结果"><a v-for="item in searchMatches" :key="item.id" :href="`#${item.id}`" @click="search = ''">{{ item.label }}</a></div><p v-else-if="search.trim()" role="status">没有匹配章节，请换一个关键词。</p><nav aria-label="使用文档章节"><a v-for="item in docLinks" :key="item.id" :href="`#${item.id}`">{{ item.label }}</a></nav></div></details>
  </header>
  <article aria-label="个人知识库与 AI 识别评测使用指南">
    <DocRow id="quickstart" title="使用指南" intro><p>从你现在想做的事情开始。</p><p>第一次使用？先<RouterLink to="/register">注册</RouterLink>或<RouterLink to="/login">登录</RouterLink>。普通笔记、词汇学习和 DEMO 实验不需要模型密钥。</p><template #detail><nav class="guide-start" aria-label="选择操作"><a href="#knowledge-notes"><span>01</span><strong>记录与整理</strong><small>写笔记、导出文件</small></a><a href="#quality-route"><span>02</span><strong>识别与实验</strong><small>上传样本、查看结果</small></a><a href="#vocabulary"><span>03</span><strong>学习词汇</strong><small>选词书、练习与复习</small></a></nav></template></DocRow>
<DocRow id="knowledge-notes" title="学习库与在线笔记"><p>不用配置 AI，也能编辑、整理和导出笔记。</p><template #detail><div class="guide-instructions"><ol><li>打开「我的笔记」，从左侧选择英语、计算机或其他学习库，点击「新建笔记」。</li><li>填写标题，选择 Markdown 或 HTML。左侧编写，右侧实时预览；也可插入学习模板。</li><li>点击「保存更改」，再选择 Markdown、PDF、Word、HTML 或 TXT 导出。刷新页面可检查保存结果。</li></ol><RouterLink to="/app/notes/new">新建笔记 →</RouterLink></div><details class="guide-more"><summary>更多说明与注意事项</summary><div><p>在<RouterLink to="/app/notes">笔记</RouterLink>中新建或打开文档，使用 Markdown / HTML 双栏编辑与预览；在<RouterLink to="/app/knowledge">知识库</RouterLink>中查看主题和知识卡。学习库可自由命名；保存第一篇笔记后新库出现在列表。旧笔记归入综合学习。笔记可导出 Markdown / PDF / Word / HTML / TXT，主动创建的只读分享可设置有效期并撤销；分享笔记不会自动开放底层文件或实验权限。</p>
          <p>HTML 用于内容排版，不执行脚本、不加载远程媒体。代码块仅展示示例；格式切换不转换源码。PDF / Word 保留常见结构，复杂 CSS 不会完整还原。知识卡中的「写成笔记 / 导出」会带入个人编辑器，保存后即可导出。</p>
          <p>「选择实验结果」只列出本人已完成实验和本人个人图片识别结果，每次最多选 20 份。先点「预览选中结果」，再「插入编辑区」，最后手动保存。服务端按当前账户校验来源，即使管理员也不能在个人来源选择器中读取别人的结果。</p>
          <p>若还要把所选来源交给个人模型，需另外勾选「同时发送上方选中的实验结果」，并核对发送预览。已把相同结果插入正文时，避免再重复勾选。个人 AI 的正文加来源合计最多 24,000 字符，超过会拒绝，不会静默截断。</p>
          <div class="docs-callout"><AppIcon name="spark" :size="20" /><div><strong>本地规则是单独的处理方式</strong><p>在「整理与写作」选「本地规则 · 无外部模型调用」，可做摘要、大纲、标签、格式整理，响应明确标记 <code>LOCAL_RULES</code>。它在平台服务端处理，不向模型供应商发送内容，也不使用个人密钥；报告草稿需要个人模型。正文超过 24,000 字符会报错并保留原文，不会截断或自动替换。</p></div></div></div></details></template></DocRow>
<DocRow id="code-notes" title="代码块与 AI 注释"><p>记录代码、解读逻辑，并用父子页面组织知识。</p><template #detail><div class="guide-instructions"><ol><li>新建 Markdown 笔记，点击「＋ 插入块 → 代码块」，选择代码语言。</li><li>在「编辑范围」切到代码块，左侧编辑，右侧查看语法高亮。悬停标识符可关联同名位置，移开清除。</li><li>选择注释方式和个人模型，点击「AI 解释并生成注释」。检查代码发送预览并确认，获得结果后手动应用、保存。</li><li>在笔记列表点击「＋ 子页面」，或在编辑器设置「父页面」；删除父页会保留子页。</li></ol><RouterLink to="/app/notes/new?library=计算机学习&amp;template=study">打开代码笔记模板 →</RouterLink><p>AI 仅静态推断，不执行代码。Java 缺少 main 时仍解释代码，并说明入口条件；伪代码解释用途，不编造运行输出。模型说明以注释加在原代码之前，原代码保留。</p></div><details class="guide-more"><summary>注释方式与权限</summary><p>Python / Shell / YAML 使用连续单行注释，HTML / CSS 使用对应合法格式，JSON 注释后变为 JSONC。只发送当前代码块，不发送整篇笔记。修改代码或语言会使旧的确认失效。同名高亮不做编译器级作用域解析。父子页面均为本人私有；分享父页不自动分享子页。</p></details></template></DocRow>
<DocRow id="quality-route" title="识别一张图片"><p>个人图片识别需要你自己的模型配置。只想体验上传与结果页，可以先运行 DEMO。</p><template #detail><div class="guide-instructions"><ol><li>打开实验台上方的「个人 AI 图片识别」。</li><li>选择模型与任务，上传票据或车牌图片。</li><li>点击「上传并预览发送内容」，检查原图和指令。</li><li>确认发送后查看结果；回到笔记也能插入这次结果。</li></ol><RouterLink to="/app/upload">打开实验台 →</RouterLink><p class="guide-outcome">暂时没有密钥？使用实验台下方的 DEMO 流程；它的结果是演示数据。</p></div><details class="guide-more"><summary>更多说明与注意事项</summary><div><p>前往<RouterLink to="/app/upload">上传页面上方的「个人 AI 图片识别」</RouterLink>，选择个人配置与票据 / 车牌任务。先确认所填模型支持图片输入；供应商名称相同并不表示其所有模型都有视觉能力。</p>
          <p>只接受属于本人、扫描状态为 <code>CLEAN</code> 的 PNG / JPEG / WebP 图片，最大 5 MiB（5,242,880 字节）。图片先上传到本平台；预览显示原图、文件名、MIME、大小、SHA-256、供应商 / 模型和完整指令，单独确认后才发送给供应商。</p>
          <div class="docs-callout docs-callout--warning"><AppIcon name="images" :size="20" /><div><strong>真实视频调用暂未开放</strong><p>此入口只支持票据和车牌图片。DeepSeek 视觉兼容性尚未验证，当前拒绝该图片路径。下方 DEMO 视频流程仍可用于检查平台链路，不代表真实视频识别能力。</p></div></div>
          <p>识别输出是需人工核对的文本 / Markdown，不保证准确率、结构化字段或置信度。结果保存到本人账户，可点击「查看 / 刷新我的识别结果」，或在笔记选择器中插入。停止等待后仍可能完成保存，先刷新结果与使用记录，避免重复调用。</p></div></details></template></DocRow>
<DocRow id="vocabulary" title="开始背单词"><p>先选一本词书，每次完成一个小目标。学习进度自动保存到当前账户。</p><template #detail><div class="guide-instructions"><ol><li>打开「背单词」，确认学习时区和每日目标并保存。</li><li>选择词书，开始四选一练习。</li><li>单词累计答对 4 次后初步掌握，次日再复习。</li></ol><RouterLink to="/app/vocabulary">开始背单词 →</RouterLink></div><details class="guide-more"><summary>更多说明与注意事项</summary><div><p>打开<RouterLink to="/app/vocabulary">背单词</RouterLink>，先确认并保存学习时区（IANA）和每日目标，再选词书。现在有 17 本内置词书，涵盖考研、四六级、雅思、托福、GRE、中小学、学术、计算机与商务。新增词库包含 14,894 个不同单词；在「我的词书」按分类或关键词搜索，查看词量后选择。具体出版社与年份版本可自行私有导入。</p>
          <ol class="developer-flow"><li><span>01</span><div><strong>四选一学习</strong><p>每个单词累计答对 4 次后标记「初步掌握」，答错不增加进度，也不清除已有正确次数。</p></div></li><li><span>02</span><div><strong>次日开始复习</strong><p>按已保存学习时区的下一个本地日历日期到期。后续答对间隔为 3、7、14、30、60 天；答错后次日重试。</p></div></li><li><span>03</span><div><strong>错词与收藏</strong><p>支持错词练习、星标、搜索、分页词表、学习目标、连续天数与 14 天记录。错词练习不增加学习次数或推进复习。</p></div></li><li><span>04</span><div><strong>导入自己的词书</strong><p>「我的词书 → 导入词书」接受 4–500 词的 JSON，每账户最多 20 本、文件最多 2 MiB。每词需 3–8 个明确错误释义，并由你确认内容使用权。</p></div></li></ol>
          <p>导入词书和进度仅本人可见，管理员也不例外；更换词书保留进度。无需 AI 密钥。这里是独立学习功能，尚无真人录音、口语评分、离线同步或提醒，不承诺复刻其他应用或其专有复习算法。</p></div></details></template></DocRow>
<DocRow id="model-contract" title="配置自己的 AI"><p>只在需要模型整理文字或识别图片时设置。没有密钥不会影响普通笔记和背单词。</p><template #detail><div class="guide-instructions"><ol><li>打开「设置 → 个人 AI」，选择供应商。</li><li>填写供应商账户实际可用的模型 ID、API 地址和密钥。</li><li>启用并保存。返回笔记或实验台，刷新个人配置。</li></ol><RouterLink to="/app/settings?section=ai">打开个人 AI 设置 →</RouterLink><p class="guide-outcome">如果提示「此部署尚未开启外部模型调用」，需要部署者开启远程开关；再次保存密钥不能解决这个提示。</p></div><details class="guide-more"><summary>更多说明与注意事项</summary><div><p>在<RouterLink to="/app/settings?section=ai">个人 AI 设置</RouterLink>中，明确选择供应商、填写你账户可用的模型 ID、核对 API 地址，再输入自己的 API Key 并启用。不会自动替你选择模型；保存配置不代表已验证密钥、模型权限或计费状态。</p>
          <p>当前有 9 类适配器：OpenAI、Google Gemini、xAI / Grok、Anthropic Claude、DeepSeek、Moonshot / Kimi、Qwen、托管 Llama、自定义 OpenAI 兼容网关。实现了 OpenAI Chat、Anthropic Messages、Gemini generateContent 三类协议；验证使用模拟上游和合成数据，不代表真实账户均可用。</p>
          <div class="docs-callout docs-callout--warning"><AppIcon name="shield" :size="20" /><div><strong>保存个人配置不会打开部署开关</strong><p><code>PERSONAL_AI_REMOTE_ENABLED=false</code> 是默认值。关闭时仍可保存配置、查看预览，不能执行远程调用。只有部署者准备好后才能启用该开关，并按正常运维流程重新创建后端容器；修改配置文件不会自动改变运行中的容器。</p></div></div>
          <p>个人密钥由服务端以 AES-256-GCM 加密保存，接口只返回是否配置等元数据，不返回密钥。更新时留空保留原密钥。个人调用只使用本人的配置，不读取管理员或其他用户的共享密钥。</p>
          <p>通过受信任的 HTTPS 设置页输入密钥；不要放入源码、<code>VITE_*</code>、浏览器本地存储、URL、日志或截图。部署者须保管独立生成、至少 32 UTF-8 字节的 <code>CREDENTIAL_MASTER_KEY</code>；丢失或直接更换会使原有加密密钥无法解密。</p>
          <p>托管 Llama 需使用 Groq、Together 等实际托管服务。Qwen 区域和密钥必须匹配。自定义或区域地址由部署者通过 <code>PERSONAL_AI_ALLOWED_BASE_URLS</code> 审核精确 HTTPS API 地址；不允许任意域名、私网或环回地址。网关运营方会接收你的密钥和获准内容。</p></div></details></template></DocRow>
<DocRow id="ai-memory" title="让不同 AI 共用你的记忆"><p>你的表达偏好、学习目标与常用背景，可以统一保存在个人记忆库。</p><template #detail><div class="guide-instructions"><ol><li>进入「设置 → 个人 AI → 共享记忆」。</li><li>填写标题与内容，例如「请用中文解释 Java，先举例再讲原理」。</li><li>勾选使用并保存；以后在笔记 AI 和图片理解中均可使用。</li><li>不想发送某条记忆时点「停用」，或直接编辑、删除。</li></ol><p>每次发送前，预览会包含本次启用的记忆。改动记忆后需要重新预览。其他账户（包括平台管理员）无法通过页面或接口读写你的记忆。</p><RouterLink to="/app/settings?section=ai">管理我的记忆 →</RouterLink></div></template></DocRow>
<DocRow id="local-training" title="训练自己的文本分类模型"><p>无需 API 密钥或显卡。在平台提供的 PyTorch CPU 环境中，用自己的标注数据训练小型分类模型。</p><template #detail><div class="guide-instructions"><ol><li>进入「设置 → 个人 AI → 本地训练」，确认环境已就绪。</li><li>下载示例 JSON，按其中的 text（文字）和 label（类别）格式准备样本。</li><li>上传文件，填写名称、轮数与学习率，再点「开始训练」。</li><li>观察训练损失、验证准确率及逐轮指标；完成后输入新文字试用。</li><li>点击「下载模型与数据」保存权重、原始样本、指标和离线预测脚本。</li></ol><p>需要 2–12 个类别，每类至少 4 条不同样本，总计 8–500 条。约 20% 留作验证；同样文字不能重复。小样本的验证结果只用于了解本次训练，不能当作通用性能结论。</p><p>记忆库用于发送背景，训练用于学习分类权重，两者分开。当前提供固定文本分类模板，不执行上传的 Python 代码，也不微调第三方聊天模型。</p><RouterLink to="/app/settings?section=ai">打开本地训练 →</RouterLink></div></template></DocRow>
<DocRow id="first-request" title="预览，然后发送"><p>先看清楚要发送的内容，再决定是否使用自己的模型额度。</p><template #detail><div class="guide-instructions"><ol><li>选择个人模型与操作，准备发送预览。</li><li>核对模型、接收地址和完整内容。</li><li>同意后勾选确认并发送；不发送就点击「取消，不发送」。</li><li>结果生成后先检查，再手动应用和保存。</li></ol><p class="guide-outcome">离开页面或停止等待，不能保证撤回供应商已经收到的请求。</p></div><details class="guide-more"><summary>开发者接口示例与限制</summary><div><ol class="developer-flow"><li><span>01</span><div><strong>准备内容</strong><p>在笔记的「整理与写作」选择已启用的个人模型，然后选摘要、大纲、标签、格式整理或报告草稿。</p></div></li><li><span>02</span><div><strong>逐项核对预览</strong><p>确认供应商、模型、实际端点、系统指令、完整发送内容和大小。预览不会调用模型。</p></div></li><li><span>03</span><div><strong>明确同意本次发送</strong><p>勾选授权并点击「确认发送并生成」。不想发送时选「取消，不发送」。</p></div></li><li><span>04</span><div><strong>审核后手动应用</strong><p>生成内容先展示结果预览；检查事实后插入、替换正文或合并标签，最后保存笔记。</p></div></li></ol>
          <p>API 使用同源地址，例如本机 <code>http://localhost:4173/api/v1</code>。先通过 <code>POST /api/v1/auth/login</code> 登录取得平台会话令牌；后续请求带 <code>Authorization: Bearer &lt;自己的平台会话令牌&gt;</code> 和 <code>Content-Type: application/json</code>。供应商密钥不放在以下请求中。</p>
          <div class="docs-code-window" aria-label="个人 AI 请求示例">
            <header><div class="docs-code-tabs" aria-label="选择请求示例"><button v-for="example in examples" :key="example.id" type="button" :class="{ active: selectedExample === example.id }" :aria-pressed="selectedExample === example.id" @click="selectedExample = example.id; copied = false">{{ example.label }}</button></div><button type="button" class="docs-copy" aria-label="复制当前示例 JSON" @click="copyCode"><AppIcon :name="copied ? 'check' : 'copy'" :size="15" />{{ copied ? '已复制' : '复制 JSON' }}</button></header>
            <div class="docs-example-context" aria-live="polite"><p>{{ activeExample.endpoint }}</p><p>{{ activeExample.description }}</p><pre><code>{{ activeCode }}</code></pre></div>
          </div>
          <p>这些是带占位符的请求体示例，不包含真实密钥，也不证明请求成功。预览返回的一次性令牌有效期为 5 分钟，绑定当前账户、完整请求和配置版本。编辑内容后须重新预览；修改或删除配置、API 重启、过期或重复使用都会使原预览失效。</p>
          <div class="docs-callout docs-callout--warning"><AppIcon name="shield" :size="20" /><div><strong>停止等待不保证撤回已发送的请求</strong><p>关闭、离开页面或停止等待会阻止迟到结果写入当前编辑区，但供应商可能已处理并计费。系统不自动重试，也不会自动切换到本地规则或共享密钥；重试前先查使用记录，再重新预览并确认。</p></div></div></div></details></template></DocRow>
<DocRow id="online-platform" title="多人平台与自动同步"><p>所有人打开同一个网站，就在使用同一台服务器。账号、笔记和消息直接保存到该服务器；部署到云端后，同一账号换设备仍能读取自己的内容。</p><p>笔记默认私密。添加联系人不会公开笔记或增加管理权限。</p><template #detail><div class="guide-instructions"><ol><li>填写笔记标题和正文，停止输入约 1.5 秒后自动保存。看到「已保存到服务器」再关闭页面。</li><li>保存失败时保留页面，重试或下载草稿；出现版本冲突时先下载当前草稿，再重新加载服务器版本。当前不支持完整离线编辑。</li><li>打开「联系人与聊天」，输入至少 2 字的用户名或昵称前缀，搜索并申请添加。</li><li>对方同意后可私聊。页面可见时约每 5 秒检查新消息；发附件可进入站内信。</li><li>在同一页面可关闭被搜索、删除或屏蔽联系人；群组成员权限独立管理。</li></ol><p>各自下载仓库启动的站点不会互通。用户数据库、笔记和聊天不会上传 GitHub。公网运行与异地备份需要自己的服务器及存储配置。</p><RouterLink to="/app/contacts">打开联系人与聊天 →</RouterLink> · <a href="https://github.com/sunlight1106/Personal-Knowledge-Base-and-AI-Recognition-Evaluation/blob/main/docs/PUBLIC_DEPLOYMENT.md" target="_blank" rel="noopener noreferrer">公网部署步骤 →</a></div></template></DocRow>
<DocRow id="group-collaboration" title="群组交流与站内信"><p>研究员可以新建群组，邀请平台账号一起讨论、回复和分享资料。</p><p>所有账号都能联系平台管理员；同组成员可按群内权限私信。群管理员的权限仅在本群生效。</p><template #detail><div class="guide-instructions"><ol><li>打开「群组协作」，点击「新建群组」并填写名称。</li><li>展开「成员与权限」，选择账号并添加为成员；只读成员不能发言。</li><li>在本群输入消息，或选择最多 5 个附件后发送；每个附件不超过 20 MB。</li><li>点击消息旁的「回复」继续讨论，点击资料名称下载。</li><li>需要单独联系管理员时，打开「站内信箱」，点击「联系管理员」。</li></ol><p>新成员能阅读本群历史；退群或被移除后，服务器会拒绝继续读取群消息和下载附件。已下载文件无法撤回。</p><RouterLink to="/app/groups">打开群组协作 →</RouterLink> · <RouterLink to="/app/mail">打开站内信箱 →</RouterLink></div></template></DocRow>
<DocRow id="account-switch" title="切换账号与登录管理"><p>右上角「目录与账户」显示当前昵称、用户名和角色，并集中提供账户设置、切换与退出入口。</p><template #detail><div class="guide-instructions"><ol><li>点击「切换账号」，先处理笔记未保存的内容。</li><li>输入另一个账号及其密码，点击「验证并切换账号」。验证失败时保留原登录，成功后原会话失效。</li><li>可选记住本机账号名称，下次点选后输入密码即可；最多 5 个名称，不保留多个登录令牌。可以移除单条记录或清空。</li><li>打开「密码与登录设备」，修改密码、查看或撤销会话；切换账号不影响其他设备的独立登录。</li><li>退出需服务器确认。退出失败时可重试，本机名称记录和服务器账号是独立的。</li></ol><RouterLink to="/account/switch">切换账号 →</RouterLink> · <RouterLink to="/app/settings?section=security">管理登录设备 →</RouterLink></div></template></DocRow>
<DocRow id="permissions" title="修改资料与下载个人数据"><p>账号设置按用途分开，不需要在一个长表单中寻找。</p><template #detail><div class="guide-instructions"><ul class="guide-link-list"><li><RouterLink to="/app/settings?section=profile">个人资料</RouterLink><span>修改显示名称和邮箱</span></li><li><RouterLink to="/app/settings?section=security">安全与登录</RouterLink><span>修改密码、退出其他设备</span></li><li><RouterLink to="/app/settings?section=privacy">隐私与数据</RouterLink><span>确认密码，下载本人 JSON 数据副本</span></li><li><RouterLink to="/app/settings?section=appearance">外观</RouterLink><span>调整主题、强调色和密度</span></li></ul></div><details class="guide-more"><summary>更多说明与注意事项</summary><div><div class="docs-field-table"><div><strong><RouterLink to="/app/settings?section=profile">个人资料</RouterLink></strong><span>显示名称与邮箱；改邮箱需确认当前密码</span><code>profile</code></div><div><strong><RouterLink to="/app/settings?section=security">安全与登录</RouterLink></strong><span>改密码、查看会话、退出其他设备；改密码使所有会话失效</span><code>security</code></div><div><strong><RouterLink to="/app/settings?section=privacy">隐私与数据</RouterLink></strong><span>确认密码后下载本人 JSON 副本；不包含密码、会话令牌或 AI 密钥</span><code>privacy</code></div><div><strong><RouterLink to="/app/settings?section=appearance">外观</RouterLink></strong><span>主题、强调色与密度按当前账户保存在此浏览器</span><code>appearance</code></div></div>
          <p>个人 AI 配置、预览、调用记录和词汇进度按本人隔离，管理员身份不授予访问其他用户个人配置的权限。平台其他功能仍受角色权限控制；不能打开笔记或上传页时，请让管理员核对相应权限。升级后旧会话可能要求重新登录。</p></div></details></template></DocRow>
<DocRow id="analysis-output" title="查询用量与下载结果"><p>先分清个人模型用量和平台演示账本。个人 AI 的费用以供应商账户为准。</p><template #detail><div class="guide-instructions"><ul class="guide-link-list"><li><RouterLink to="/app/settings?section=usage">个人使用情况</RouterLink><span>查模型调用、Tokens 和错误记录</span></li><li><RouterLink to="/app/downloads">下载中心</RouterLink><span>下载可访问的素材和实验 JSON 报告</span></li><li><RouterLink to="/app/notes">我的笔记</RouterLink><span>打开笔记，导出 Markdown、PDF、Word、HTML 或 TXT</span></li></ul></div><details class="guide-more"><summary>更多说明与注意事项</summary><div><p><RouterLink to="/app/settings?section=usage">设置 → 使用情况</RouterLink>展示本人全部历史汇总，以及最近最多 100 次个人 AI 调用。仅做预览不计入调用记录；用量以供应商实际返回为准。</p>
          <div class="docs-field-table"><div><strong>个人汇总</strong><span>AI 调用、平台实验、文件存储、笔记与图片识别结果等本人统计</span><code>/api/v1/account/usage</code></div><div><strong>最近调用</strong><span>供应商、模型、操作、时间、状态、已报告 Tokens 和脱敏错误码</span><code>/api/v1/personal-ai/usage</code></div></div>
          <p>未提供的 Token 数记为未知，不视为零费用。失败、超时或停止等待的请求仍可能由供应商计费；平台不估算个人 AI 费用、不扣平台钱包，也不展示未经验证的供应商余额。实际价格、余额与额度以自己的供应商账户为准。平台充值页面是本地计费沙箱，不发生真实支付。</p></div></details></template></DocRow>
<DocRow id="requirements" title="下载并启动平台"><p>如果你已经打开了正在运行的平台，直接跳过这节。首次部署只需要 Docker 和项目源码。</p><template #detail><div class="guide-instructions"><ol><li>在 GitHub 点击「Code → Download ZIP」，解压到一个固定目录；也可以用 Git 克隆。</li><li>安装 Docker Desktop，使用 Linux containers。</li><li>在解压后的目录打开 PowerShell，运行下方 Windows 命令。</li><li>等到显示 Ready，打开输出的网址。</li></ol><pre class="guide-command"><code>powershell -ExecutionPolicy Bypass -File .\deploy.ps1</code></pre><p>Linux / macOS：<code>sh deploy.sh</code>。首次启动后，在本机 <code>.env</code> 查看管理员密码；之后 Windows 可双击 <code>start.cmd</code>。</p><a href="https://github.com/sunlight1106/Personal-Knowledge-Base-and-AI-Recognition-Evaluation#readme" target="_blank" rel="noreferrer">完整安装、更新与排错说明 ↗</a></div><details class="guide-more"><summary>更多说明与注意事项</summary><div><p>已有可访问的平台可以跳过这一步。自托管时先安装并启动 Docker Engine / Docker Desktop（Linux containers）与 Compose 2.20.2+，再在仓库根目录运行对应脚本。首次构建需要下载依赖和病毒库。</p>
          <div class="docs-code-window docs-code-window--light"><header><span>任选与你的系统对应的一行；不是模型调用命令</span></header><pre><code>Windows PowerShell: powershell -ExecutionPolicy Bypass -File .\deploy.ps1
Linux / macOS: sh deploy.sh</code></pre></div>
          <p>Windows 脚本会尝试启动 Docker Desktop；Linux / macOS 需先自行启动引擎。首次运行生成含随机管理员密码及服务密钥的私有 <code>.env</code>，随后构建并等待服务健康。不要提交或分享该文件，也不要用空白模板覆盖它。</p>
          <div class="requirements-grid"><article><AppIcon name="terminal" :size="21" /><strong>本机入口 :4173</strong><span>默认 http://localhost:4173；自定义端口以 WEB_PORT 为准</span></article><article><AppIcon name="key" :size="21" /><strong>首次管理员登录</strong><span>用户名 admin；密码由部署者在本机 .env 的 BOOTSTRAP_ADMIN_PASSWORD 中查看</span></article><article><AppIcon name="shield" :size="21" /><strong>安全默认</strong><span>仅监听本机；远程个人 AI 关闭；无需公共供应商密钥</span></article></div>
          <p>Compose 仅对宿主机开放前端端口，API 通过同源 <code>/api/</code> 代理。<a href="/swagger-ui.html" target="_blank" rel="noreferrer">API Reference</a> 也通过前端代理访问。向其他设备提供服务前，由部署者配置 HTTPS、访问控制和备份。</p></div></details></template></DocRow>
<DocRow id="errors" title="遇到问题，先看这里"><p>按照屏幕上的提示处理。出错后反复点击发送，可能造成重复模型调用。</p><template #detail><div class="guide-instructions"><ul class="guide-link-list"><li><strong>网址打不开</strong><span>确认 Docker 已运行，执行启动脚本；网址以 Ready 后的端口为准。</span></li><li><strong>登录过期</strong><span>重新登录。升级后的旧会话可能失效。</span></li><li><strong>没有可选个人模型</strong><span>在个人 AI 设置中保存并启用配置，然后刷新。</span></li><li><strong>能预览，不能发送</strong><span>检查远程调用开关；它默认关闭。</span></li><li><strong>识别等待太久</strong><span>先刷新我的识别结果和用量，确认状态后再决定是否重试。</span></li></ul></div><details class="guide-more"><summary>更多说明与注意事项</summary><div><div class="error-guide">
            <details open><summary><code>PERSONAL_AI_REMOTE_DISABLED</code><span>能预览，但不能发送</span><AppIcon name="chevron" :size="16" /></summary><p>这是默认安全状态。请部署者检查远程开关和运行中容器的配置。个人用户保存密钥不会开启它；此时仍可使用笔记、本地规则与背单词。</p></details>
            <details><summary><code>PERSONAL_AI_CONFIG_REQUIRED</code><span>缺少已启用的个人配置</span><AppIcon name="chevron" :size="16" /></summary><p>到「设置 → 个人 AI」保存自己的供应商、明确模型 ID 与密钥并启用，再回笔记刷新配置。不会代用其他用户或管理员密钥。</p></details>
            <details><summary><code>PERSONAL_AI_PREVIEW_INVALID</code><span>预览过期或已使用</span><AppIcon name="chevron" :size="16" /></summary><p>取消旧预览，重新核对当前内容再确认。修改配置也可能返回 PERSONAL_AI_CONFIG_CHANGED。不要重复提交旧令牌。</p></details>
            <details><summary><code>PERSONAL_AI_CONTEXT_TOO_LARGE</code><span>正文加来源超过限制</span><AppIcon name="chevron" :size="16" /></summary><p>减少所选来源或自行拆分笔记后重新预览。单次正文与来源合计最多 24,000 字符；本地整理超长会返回 ASSIST_BODY_TOO_LARGE，原文不被截断。</p></details>
            <details><summary><code>RECOGNITION_REQUEST_INVALID</code><span>图片、模型或预览不符合要求</span><AppIcon name="chevron" :size="16" /></summary><p>核对本人图片、CLEAN 扫描状态、PNG / JPEG / WebP 格式与 5 MiB 上限，以及个人视觉模型配置。真实视频与 DeepSeek 图片路径当前不可用。</p></details>
            <details><summary><code>调用失败或用量未知</code><span>先检查记录，避免重复扣费</span><AppIcon name="chevron" :size="16" /></summary><p>查看使用记录和供应商账户，确认模型权限、余额、地区与端点匹配。图片请求可先刷新本人结果。不要在错误截图、聊天或日志中附密钥、预览令牌或私人原文；向部署者提供脱敏错误码与 traceId 即可。</p></details>
          </div></div></details></template></DocRow>
<DocRow id="architecture" title="服务与演示模式"><p>这部分供部署和开发时查阅。日常使用无需操作数据库或队列。</p><template #detail><div class="guide-instructions"><p>页面入口 → 后端接口 → 数据库与存储。</p><p>DEMO 实验另外经过队列和 Worker。个人 AI 走预览与确认流程。</p></div><details class="guide-more"><summary>更多说明与注意事项</summary><div><div class="docs-service-grid"><article><b>MySQL</b><span>业务数据与个人配置的加密记录</span></article><article><b>Redis</b><span>旧实验队列与部分平台限流</span></article><article><b>MinIO + ClamAV</b><span>对象存储与上传扫描</span></article><article><b>model-worker</b><span>消费旧实验队列与媒体预处理</span></article><article><b>Spring Boot API</b><span>鉴权、个人预览与确认后调用</span></article><article><b>Vue + Nginx</b><span>唯一默认对外入口 :4173</span></article></div>
          <p>旧「平台演示与双路对比」使用 <code>MODEL_MODE=demo</code>：图片增强、视频音轨预处理、异步状态和结果页用于验证链路，输出明确标记 <code>DEMO</code>，属于合成结果。旧公共密钥远程执行入口已停用，不能切换模式来绕过个人确认流程。</p>
          <p>个人 AI 通过 API 的独立预览 / 执行流程运行，不走旧模型队列。预览和细粒度并发限制位于单个 API 进程内；API 重启会清除待确认预览。扩容前需要单副本或粘性路由处理预览，并配置共享网关配额，不能把进程内限流视为整个集群的费用上限。</p></div></details></template></DocRow>
<DocRow id="training-data" title="导出实验数据"><p>导出便于保存、复查与后续处理，不代表完成模型训练。</p><template #detail><div class="guide-instructions"><ol><li>先确认已有可访问的实验与素材。</li><li>在模型中心使用数据集导出入口。</li><li>检查 ZIP 内的清单、标签与划分信息。</li></ol><RouterLink to="/app/models">打开模型中心 →</RouterLink></div><details class="guide-more"><summary>更多说明与注意事项</summary><div><p>旧实验的 <code>GET /api/v1/training/dataset</code> 可以导出数据集 ZIP、清单与划分信息。DEMO 输出是合成数据，不可当作人工真值、真实准确率或论文结论；模型标签也必须人工复核。</p>
          <p>个人 AI 设置已提供本地 PyTorch 文本分类训练；供应商微调和大模型 checkpoint 管理尚未实现。导出文件、媒体处理成功或模拟协议测试通过，都不代表模型已训练、真实视频接口可用或识别质量提高。</p></div></details></template></DocRow>
<DocRow id="acceptance" title="检查是否操作成功"><p>用一个不含敏感信息的小样本，走完一次流程。</p><template #detail><div class="guide-instructions"><ol><li>保存笔记后刷新，标题和正文仍在。</li><li>完成一次词汇练习后刷新，进度仍在。</li><li>运行 DEMO 后能打开结果，并在下载中心下载报告。</li><li>个人 AI 只预览并取消时，不会发出模型调用。</li></ol></div><details class="guide-more"><summary>更多说明与注意事项</summary><div><ol>
            <li><RouterLink to="/app/notes/new">新建笔记</RouterLink>，输入不含敏感信息的练习文字，选本地规则摘要；检查结果后应用、保存，刷新确认仍存在。</li>
            <li>有本人已完成实验时，在笔记中选择来源、预览并插入，再保存。没有来源时看到空状态是正常的，不会生成虚构实验。</li>
            <li><RouterLink to="/app/vocabulary">打开背单词</RouterLink>，确认时区、设置 1 词目标，答错一次后再累计答对 4 次，检查 4/4 与次日复习日期；刷新检查进度保留。</li>
            <li><RouterLink to="/app/settings?section=profile">检查个人设置</RouterLink>与<RouterLink to="/app/settings?section=usage">使用情况</RouterLink>。未发起个人模型调用时，AI 记录为空或为零是正常的。</li>
            <li>需要真实 AI 时再配置自己的密钥与模型。部署远程开关关闭时，验证预览与「取消，不发送」即可；不可把禁用的发送按钮记为真实调用通过。</li>
            <li>仅在部署已启用且你同意供应商费用时，确认一次纯练习文本调用；如需图片，再选择有权使用的非敏感票据 / 车牌示例，核对原图与指令，单独确认。检查本人结果和用量。</li>
            <li>退出后换另一个测试账户，确认看不到前一个账户的个人 AI 配置、私有词书与进度。次日本地日期再验收复习到期；不要修改正式数据库日期。</li>
          </ol>
          <p>这是一份操作清单，不是这些步骤已在你的部署执行完成的声明。自动化模拟验证不能替代真实模型联调、浏览器视觉检查和你自己的账户验收。</p></div></details></template></DocRow>
<DocRow id="interactive-notes" title="阅读代码示例"><p>悬停变量可查看对应关系，移开即取消高亮；点击可定位定义行，不会留下整行底色。使用 Tab 也可聚焦并打开链接。</p><p>下方仅演示阅读交互，不会发送实验或模型请求。</p></DocRow><AnnotatedExample /></article><footer class="lab-footer"><RouterLink to="/app/home">返回工作台</RouterLink><span>更新于 2026-10-05</span></footer></div></template>
