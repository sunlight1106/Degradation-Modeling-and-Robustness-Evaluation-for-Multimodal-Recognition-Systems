<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import AppLogo from '@/components/AppLogo.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'

const copied = ref(false)
const activeSection = ref('quickstart')
const search = ref('')
const searchInput = ref<HTMLInputElement | null>(null)
const docsStyle = ref<Record<string, string>>({ '--docs-rx': '0deg', '--docs-ry': '0deg' })
let observer: IntersectionObserver | null = null
let scrollFrame = 0
let copyTimer = 0
let mounted = true

const docLinks = [
  { id: 'quickstart', label: '快速开始', keywords: '登录 注册 first run' },
  { id: 'requirements', label: '启动与部署', keywords: 'Docker localhost 4173 环境' },
  { id: 'model-contract', label: '个人 AI 配置', keywords: 'BYOK key 密钥 供应商 模型 remote' },
  { id: 'first-request', label: '发送预览与确认', keywords: 'API JSON preview execute 取消' },
  { id: 'quality-route', label: '票据与车牌图片识别', keywords: 'image recognition 视频 5 MiB' },
  { id: 'knowledge-notes', label: '笔记与实验来源', keywords: 'LOCAL_RULES 本地规则 知识库 24000 24,000' },
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
    description: 'fileId 必须替换为自己上传、已通过扫描且不超过 5 MiB 的图片 ID；taskType 可选 RECEIPT 或 LICENSE_PLATE。',
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

function moveDocs(event: MouseEvent) {
  const x = event.clientX / window.innerWidth - 0.5
  const y = event.clientY / window.innerHeight - 0.5
  docsStyle.value = { '--docs-rx': `${(-y * 1.6).toFixed(2)}deg`, '--docs-ry': `${(x * 2).toFixed(2)}deg` }
}

function openFirstSearch() {
  const first = searchMatches.value[0]
  if (!first) return
  document.getElementById(first.id)?.scrollIntoView({ behavior: 'smooth' })
  search.value = ''
}

function keyboard(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault(); searchInput.value?.focus()
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

<template>
  <div class="docs-public-shell">
    <header class="docs-public-nav">
      <RouterLink to="/" aria-label="Personal Platform 首页"><AppLogo /></RouterLink>
      <nav aria-label="公开页面"><RouterLink to="/">首页</RouterLink><RouterLink to="/models">模型</RouterLink><a href="#quickstart" aria-current="page">文档</a></nav>
      <div><span v-if="authStore.state.user" class="pp-current-user"><i>{{ authStore.state.user.displayName.slice(0, 1) }}</i>{{ authStore.state.user.displayName }}</span><RouterLink v-else to="/login">登录</RouterLink><RouterLink :to="authStore.state.user ? '/app/home' : '/login'" class="pp-enter-button">进入平台 <AppIcon name="arrow" :size="15" /></RouterLink></div>
    </header>
    <div class="developer-docs" :style="docsStyle" @mousemove="moveDocs">
      <aside class="developer-sidebar">
        <label class="docs-search"><AppIcon name="search" :size="16" /><input ref="searchInput" v-model="search" aria-label="搜索使用文档" placeholder="搜索文档" @keydown.enter="openFirstSearch" /><kbd>⌘ K</kbd></label>
        <div v-if="searchMatches.length" class="docs-search-results" aria-label="文档搜索结果"><a v-for="item in searchMatches" :key="item.id" :href="`#${item.id}`" @click="search = ''">{{ item.label }}</a></div>
        <p v-else-if="search.trim()" role="status">没有匹配章节，请换一个关键词。</p>
        <nav aria-label="使用文档章节"><div><p>USER GUIDE</p><a v-for="item in docLinks" :key="item.id" :class="{ active: activeSection === item.id }" :aria-current="activeSection === item.id ? 'location' : undefined" :href="`#${item.id}`">{{ item.label }}</a></div></nav>
        <a class="docs-api-link" href="/swagger-ui.html" target="_blank" rel="noreferrer"><AppIcon name="terminal" :size="17" /> API Reference <AppIcon name="arrow" :size="14" /></a>
      </aside>

      <article class="developer-article" aria-label="Personal Platform 使用指南">
        <header id="quickstart" class="developer-hero">
          <p class="docs-breadcrumb">使用文档 <span>/</span> First run</p>
          <h1>从你的第一篇笔记开始</h1>
          <p>管理个人笔记、选择自己的实验结果、学习词汇；需要外部 AI 时，再配置自己的密钥并逐次确认发送内容。</p>
          <div class="developer-hero-actions"><RouterLink to="/app/notes/new">新建笔记 <AppIcon name="arrow" :size="16" /></RouterLink><RouterLink to="/app/vocabulary">开始背单词</RouterLink></div>
          <div class="docs-callout"><AppIcon name="shield" :size="20" /><div><strong>不配置模型密钥也能开始</strong><p>先<RouterLink to="/register">注册</RouterLink>或<RouterLink to="/login">登录</RouterLink>。笔记、本地规则整理和词汇学习均不需要供应商密钥。个人 AI 远程执行默认关闭；协议已做模拟验证，尚未进行真实供应商联调。</p></div></div>
        </header>

        <section id="requirements" class="developer-section">
          <div class="developer-step-title"><span>1</span><div><p>SET UP</p><h2>启动本地平台</h2></div></div>
          <p>已有可访问的平台可以跳过这一步。自托管时先安装并启动 Docker Engine / Docker Desktop（Linux containers）与 Compose 2.20.2+，再在仓库根目录运行对应脚本。首次构建需要下载依赖和病毒库。</p>
          <div class="docs-code-window docs-code-window--light"><header><span>任选与你的系统对应的一行；不是模型调用命令</span></header><pre><code>Windows PowerShell: powershell -ExecutionPolicy Bypass -File .\deploy.ps1
Linux / macOS: sh deploy.sh</code></pre></div>
          <p>脚本检测 Docker；若引擎未启动会退出，需要你启动后重试。首次运行生成含随机管理员密码及服务密钥的私有 <code>.env</code>，随后构建并等待服务健康。不要提交或分享该文件，也不要用空白模板覆盖它。</p>
          <div class="requirements-grid"><article><AppIcon name="terminal" :size="21" /><strong>本机入口 :4173</strong><span>默认 http://localhost:4173；自定义端口以 WEB_PORT 为准</span></article><article><AppIcon name="key" :size="21" /><strong>首次管理员登录</strong><span>用户名 admin；密码由部署者在本机 .env 的 BOOTSTRAP_ADMIN_PASSWORD 中查看</span></article><article><AppIcon name="shield" :size="21" /><strong>安全默认</strong><span>仅监听本机；远程个人 AI 关闭；无需公共供应商密钥</span></article></div>
          <p>Compose 仅对宿主机开放前端端口，API 通过同源 <code>/api/</code> 代理。<a href="/swagger-ui.html" target="_blank" rel="noreferrer">API Reference</a> 也通过前端代理访问。向其他设备提供服务前，由部署者配置 HTTPS、访问控制和备份。</p>
        </section>

        <section id="model-contract" class="developer-section">
          <div class="developer-step-title"><span>2</span><div><p>YOUR OWN KEY</p><h2>设置 → 个人 AI</h2></div></div>
          <p>在<RouterLink to="/app/settings?section=ai">个人 AI 设置</RouterLink>中，明确选择供应商、填写你账户可用的模型 ID、核对 API 地址，再输入自己的 API Key 并启用。不会自动替你选择模型；保存配置不代表已验证密钥、模型权限或计费状态。</p>
          <p>当前有 9 类适配器：OpenAI、Google Gemini、xAI / Grok、Anthropic Claude、DeepSeek、Moonshot / Kimi、Qwen、托管 Llama、自定义 OpenAI 兼容网关。实现了 OpenAI Chat、Anthropic Messages、Gemini generateContent 三类协议；验证使用模拟上游和合成数据，不代表真实账户均可用。</p>
          <div class="docs-callout docs-callout--warning"><AppIcon name="shield" :size="20" /><div><strong>保存个人配置不会打开部署开关</strong><p><code>PERSONAL_AI_REMOTE_ENABLED=false</code> 是默认值。关闭时仍可保存配置、查看预览，不能执行远程调用。只有部署者准备好后才能启用该开关，并按正常运维流程重新创建后端容器；修改配置文件不会自动改变运行中的容器。</p></div></div>
          <p>个人密钥由服务端以 AES-256-GCM 加密保存，接口只返回是否配置等元数据，不返回密钥。更新时留空保留原密钥。个人调用只使用本人的配置，不读取管理员或其他用户的共享密钥。</p>
          <p>通过受信任的 HTTPS 设置页输入密钥；不要放入源码、<code>VITE_*</code>、浏览器本地存储、URL、日志或截图。部署者须保管独立生成、至少 32 UTF-8 字节的 <code>CREDENTIAL_MASTER_KEY</code>；丢失或直接更换会使原有加密密钥无法解密。</p>
          <p>托管 Llama 需使用 Groq、Together 等实际托管服务。Qwen 区域和密钥必须匹配。自定义或区域地址由部署者通过 <code>PERSONAL_AI_ALLOWED_BASE_URLS</code> 审核精确 HTTPS API 地址；不允许任意域名、私网或环回地址。网关运营方会接收你的密钥和获准内容。</p>
        </section>

        <section id="first-request" class="developer-section">
          <div class="developer-step-title"><span>3</span><div><p>PREVIEW THEN CONFIRM</p><h2>每次先预览，再确认发送</h2></div></div>
          <ol class="developer-flow"><li><span>01</span><div><strong>准备内容</strong><p>在笔记的「整理与写作」选择已启用的个人模型，然后选摘要、大纲、标签、格式整理或报告草稿。</p></div></li><li><span>02</span><div><strong>逐项核对预览</strong><p>确认供应商、模型、实际端点、系统指令、完整发送内容和大小。预览不会调用模型。</p></div></li><li><span>03</span><div><strong>明确同意本次发送</strong><p>勾选授权并点击「确认发送并生成」。不想发送时选「取消，不发送」。</p></div></li><li><span>04</span><div><strong>审核后手动应用</strong><p>生成内容先展示结果预览；检查事实后插入、替换正文或合并标签，最后保存笔记。</p></div></li></ol>
          <p>API 使用同源地址，例如本机 <code>http://localhost:4173/api/v1</code>。先通过 <code>POST /api/v1/auth/login</code> 登录取得平台会话令牌；后续请求带 <code>Authorization: Bearer &lt;自己的平台会话令牌&gt;</code> 和 <code>Content-Type: application/json</code>。供应商密钥不放在以下请求中。</p>
          <div class="docs-code-window" aria-label="个人 AI 请求示例">
            <header><div class="docs-code-tabs" aria-label="选择请求示例"><button v-for="example in examples" :key="example.id" type="button" :class="{ active: selectedExample === example.id }" :aria-pressed="selectedExample === example.id" @click="selectedExample = example.id; copied = false">{{ example.label }}</button></div><button type="button" class="docs-copy" aria-label="复制当前示例 JSON" @click="copyCode"><AppIcon :name="copied ? 'check' : 'copy'" :size="15" />{{ copied ? '已复制' : '复制 JSON' }}</button></header>
            <div class="docs-example-context" aria-live="polite"><p>{{ activeExample.endpoint }}</p><p>{{ activeExample.description }}</p><pre><code>{{ activeCode }}</code></pre></div>
          </div>
          <p>这些是带占位符的请求体示例，不包含真实密钥，也不证明请求成功。预览返回的一次性令牌有效期为 5 分钟，绑定当前账户、完整请求和配置版本。编辑内容后须重新预览；修改或删除配置、API 重启、过期或重复使用都会使原预览失效。</p>
          <div class="docs-callout docs-callout--warning"><AppIcon name="shield" :size="20" /><div><strong>停止等待不保证撤回已发送的请求</strong><p>关闭、离开页面或停止等待会阻止迟到结果写入当前编辑区，但供应商可能已处理并计费。系统不自动重试，也不会自动切换到本地规则或共享密钥；重试前先查使用记录，再重新预览并确认。</p></div></div>
        </section>

        <section id="quality-route" class="developer-section">
          <div class="developer-step-title"><span>4</span><div><p>PERSONAL VISION</p><h2>票据与车牌图片识别</h2></div></div>
          <p>前往<RouterLink to="/app/upload">上传页面上方的「个人 AI 图片识别」</RouterLink>，选择个人配置与票据 / 车牌任务。先确认所填模型支持图片输入；供应商名称相同并不表示其所有模型都有视觉能力。</p>
          <p>只接受属于本人、扫描状态为 <code>CLEAN</code> 的 PNG / JPEG / WebP 图片，最大 5 MiB（5,242,880 字节）。图片先上传到本平台；预览显示原图、文件名、MIME、大小、SHA-256、供应商 / 模型和完整指令，单独确认后才发送给供应商。</p>
          <div class="docs-callout docs-callout--warning"><AppIcon name="images" :size="20" /><div><strong>真实视频调用暂未开放</strong><p>此入口只支持票据和车牌图片。DeepSeek 视觉兼容性尚未验证，当前拒绝该图片路径。下方 DEMO 视频流程仍可用于检查平台链路，不代表真实视频识别能力。</p></div></div>
          <p>识别输出是需人工核对的文本 / Markdown，不保证准确率、结构化字段或置信度。结果保存到本人账户，可点击「查看 / 刷新我的识别结果」，或在笔记选择器中插入。停止等待后仍可能完成保存，先刷新结果与使用记录，避免重复调用。</p>
        </section>

        <section id="knowledge-notes" class="developer-section">
          <div class="developer-step-title"><span>5</span><div><p>KNOWLEDGE & NOTES</p><h2>自己的笔记，自己的实验来源</h2></div></div>
          <p>在<RouterLink to="/app/notes">笔记</RouterLink>中新建或打开文档，使用 Markdown 编辑与预览；在<RouterLink to="/app/knowledge">知识库</RouterLink>中查看主题和知识卡。笔记可导出 Markdown / PDF / Word，主动创建的只读分享可设置有效期并撤销；分享笔记不会自动开放底层文件或实验权限。</p>
          <p>「选择实验结果」只列出本人已完成实验和本人个人图片识别结果，每次最多选 20 份。先点「预览选中结果」，再「插入编辑区」，最后手动保存。服务端按当前账户校验来源，即使管理员也不能在个人来源选择器中读取别人的结果。</p>
          <p>若还要把所选来源交给个人模型，需另外勾选「同时发送上方选中的实验结果」，并核对发送预览。已把相同结果插入正文时，避免再重复勾选。个人 AI 的正文加来源合计最多 24,000 字符，超过会拒绝，不会静默截断。</p>
          <div class="docs-callout"><AppIcon name="spark" :size="20" /><div><strong>本地规则是单独的处理方式</strong><p>在「整理与写作」选「本地规则 · 无外部模型调用」，可做摘要、大纲、标签、格式整理，响应明确标记 <code>LOCAL_RULES</code>。它在平台服务端处理，不向模型供应商发送内容，也不使用个人密钥；报告草稿需要个人模型。正文超过 24,000 字符会报错并保留原文，不会截断或自动替换。</p></div></div>
        </section>

        <section id="permissions" class="developer-section">
          <div class="developer-step-title"><span>6</span><div><p>YOUR ACCOUNT</p><h2>账户与隐私设置</h2></div></div>
          <div class="docs-field-table"><div><strong><RouterLink to="/app/settings?section=profile">个人资料</RouterLink></strong><span>显示名称与邮箱；改邮箱需确认当前密码</span><code>profile</code></div><div><strong><RouterLink to="/app/settings?section=security">安全与登录</RouterLink></strong><span>改密码、查看会话、退出其他设备；改密码使所有会话失效</span><code>security</code></div><div><strong><RouterLink to="/app/settings?section=privacy">隐私与数据</RouterLink></strong><span>确认密码后下载本人 JSON 副本；不包含密码、会话令牌或 AI 密钥</span><code>privacy</code></div><div><strong><RouterLink to="/app/settings?section=appearance">外观</RouterLink></strong><span>主题、强调色与密度按当前账户保存在此浏览器</span><code>appearance</code></div></div>
          <p>个人 AI 配置、预览、调用记录和词汇进度按本人隔离，管理员身份不授予访问其他用户个人配置的权限。平台其他功能仍受角色权限控制；不能打开笔记或上传页时，请让管理员核对相应权限。升级后旧会话可能要求重新登录。</p>
        </section>

        <section id="analysis-output" class="developer-section">
          <div class="developer-step-title"><span>7</span><div><p>USAGE & COST</p><h2>查看实际调用与用量</h2></div></div>
          <p><RouterLink to="/app/settings?section=usage">设置 → 使用情况</RouterLink>展示本人全部历史汇总，以及最近最多 100 次个人 AI 调用。仅做预览不计入调用记录；用量以供应商实际返回为准。</p>
          <div class="docs-field-table"><div><strong>个人汇总</strong><span>AI 调用、平台实验、文件存储、笔记与图片识别结果等本人统计</span><code>/api/v1/account/usage</code></div><div><strong>最近调用</strong><span>供应商、模型、操作、时间、状态、已报告 Tokens 和脱敏错误码</span><code>/api/v1/personal-ai/usage</code></div></div>
          <p>未提供的 Token 数记为未知，不视为零费用。失败、超时或停止等待的请求仍可能由供应商计费；平台不估算个人 AI 费用、不扣平台钱包，也不展示未经验证的供应商余额。实际价格、余额与额度以自己的供应商账户为准。平台充值页面是本地计费沙箱，不发生真实支付。</p>
        </section>

        <section id="vocabulary" class="developer-section">
          <div class="developer-step-title"><span>8</span><div><p>VOCABULARY</p><h2>背单词与私有词书</h2></div></div>
          <p>打开<RouterLink to="/app/vocabulary">背单词</RouterLink>，先确认并保存学习时区（IANA）和每日目标，再选词书。目前有 3 本原创入门词书：日常、旅行、学习 / 研究，每本 20 词，共 60 词。它们是入门选集，不是完整考试词库。</p>
          <ol class="developer-flow"><li><span>01</span><div><strong>四选一学习</strong><p>每个单词累计答对 4 次后标记「初步掌握」，答错不增加进度，也不清除已有正确次数。</p></div></li><li><span>02</span><div><strong>次日开始复习</strong><p>按已保存学习时区的下一个本地日历日期到期。后续答对间隔为 3、7、14、30、60 天；答错后次日重试。</p></div></li><li><span>03</span><div><strong>错词与收藏</strong><p>支持错词练习、星标、搜索、分页词表、学习目标、连续天数与 14 天记录。错词练习不增加学习次数或推进复习。</p></div></li><li><span>04</span><div><strong>导入自己的词书</strong><p>「我的词书 → 导入词书」接受 4–500 词的 JSON，每账户最多 20 本、文件最多 2 MiB。每词需 3–8 个明确错误释义，并由你确认内容使用权。</p></div></li></ol>
          <p>导入词书和进度仅本人可见，管理员也不例外；更换词书保留进度。无需 AI 密钥。这里是独立学习功能，尚无真人录音、口语评分、离线同步或提醒，不承诺复刻其他应用或其专有复习算法。</p>
        </section>

        <section id="architecture" class="developer-section">
          <div class="developer-step-title"><span>9</span><div><p>SERVICE BOUNDARIES</p><h2>了解服务与 DEMO 实验</h2></div></div>
          <div class="docs-service-grid"><article><b>MySQL</b><span>业务数据与个人配置的加密记录</span></article><article><b>Redis</b><span>旧实验队列与部分平台限流</span></article><article><b>MinIO + ClamAV</b><span>对象存储与上传扫描</span></article><article><b>model-worker</b><span>消费旧实验队列与媒体预处理</span></article><article><b>Spring Boot API</b><span>鉴权、个人预览与确认后调用</span></article><article><b>Vue + Nginx</b><span>唯一默认对外入口 :4173</span></article></div>
          <p>旧「平台演示与双路对比」使用 <code>MODEL_MODE=demo</code>：图片增强、视频音轨预处理、异步状态和结果页用于验证链路，输出明确标记 <code>DEMO</code>，属于合成结果。旧公共密钥远程执行入口已停用，不能切换模式来绕过个人确认流程。</p>
          <p>个人 AI 通过 API 的独立预览 / 执行流程运行，不走旧模型队列。预览和细粒度并发限制位于单个 API 进程内；API 重启会清除待确认预览。扩容前需要单副本或粘性路由处理预览，并配置共享网关配额，不能把进程内限流视为整个集群的费用上限。</p>
        </section>

        <section id="training-data" class="developer-section">
          <div class="developer-step-title"><span>10</span><div><p>DATASET LIMITS</p><h2>数据导出不等于训练完成</h2></div></div>
          <p>旧实验的 <code>GET /api/v1/training/dataset</code> 可以导出数据集 ZIP、清单与划分信息。DEMO 输出是合成数据，不可当作人工真值、真实准确率或论文结论；模型标签也必须人工复核。</p>
          <p>当前未实现供应商训练任务、微调 job 或 checkpoint 管理。导出文件、媒体处理成功或模拟协议测试通过，都不代表模型已训练、真实视频接口可用或识别质量提高。</p>
        </section>

        <section id="acceptance" class="developer-section">
          <div class="developer-step-title"><span>11</span><div><p>FIRST-RUN CHECKLIST</p><h2>按这个顺序检查首次体验</h2></div></div>
          <ol>
            <li><RouterLink to="/app/notes/new">新建笔记</RouterLink>，输入不含敏感信息的练习文字，选本地规则摘要；检查结果后应用、保存，刷新确认仍存在。</li>
            <li>有本人已完成实验时，在笔记中选择来源、预览并插入，再保存。没有来源时看到空状态是正常的，不会生成虚构实验。</li>
            <li><RouterLink to="/app/vocabulary">打开背单词</RouterLink>，确认时区、设置 1 词目标，答错一次后再累计答对 4 次，检查 4/4 与次日复习日期；刷新检查进度保留。</li>
            <li><RouterLink to="/app/settings?section=profile">检查个人设置</RouterLink>与<RouterLink to="/app/settings?section=usage">使用情况</RouterLink>。未发起个人模型调用时，AI 记录为空或为零是正常的。</li>
            <li>需要真实 AI 时再配置自己的密钥与模型。部署远程开关关闭时，验证预览与「取消，不发送」即可；不可把禁用的发送按钮记为真实调用通过。</li>
            <li>仅在部署已启用且你同意供应商费用时，确认一次纯练习文本调用；如需图片，再选择有权使用的非敏感票据 / 车牌示例，核对原图与指令，单独确认。检查本人结果和用量。</li>
            <li>退出后换另一个测试账户，确认看不到前一个账户的个人 AI 配置、私有词书与进度。次日本地日期再验收复习到期；不要修改正式数据库日期。</li>
          </ol>
          <p>这是一份操作清单，不是这些步骤已在你的部署执行完成的声明。自动化模拟验证不能替代真实模型联调、浏览器视觉检查和你自己的账户验收。</p>
        </section>

        <section id="errors" class="developer-section">
          <div class="developer-step-title"><span>12</span><div><p>TROUBLESHOOT</p><h2>常见问题</h2></div></div>
          <div class="error-guide">
            <details open><summary><code>PERSONAL_AI_REMOTE_DISABLED</code><span>能预览，但不能发送</span><AppIcon name="chevron" :size="16" /></summary><p>这是默认安全状态。请部署者检查远程开关和运行中容器的配置。个人用户保存密钥不会开启它；此时仍可使用笔记、本地规则与背单词。</p></details>
            <details><summary><code>PERSONAL_AI_CONFIG_REQUIRED</code><span>缺少已启用的个人配置</span><AppIcon name="chevron" :size="16" /></summary><p>到「设置 → 个人 AI」保存自己的供应商、明确模型 ID 与密钥并启用，再回笔记刷新配置。不会代用其他用户或管理员密钥。</p></details>
            <details><summary><code>PERSONAL_AI_PREVIEW_INVALID</code><span>预览过期或已使用</span><AppIcon name="chevron" :size="16" /></summary><p>取消旧预览，重新核对当前内容再确认。修改配置也可能返回 PERSONAL_AI_CONFIG_CHANGED。不要重复提交旧令牌。</p></details>
            <details><summary><code>PERSONAL_AI_CONTEXT_TOO_LARGE</code><span>正文加来源超过限制</span><AppIcon name="chevron" :size="16" /></summary><p>减少所选来源或自行拆分笔记后重新预览。单次正文与来源合计最多 24,000 字符；本地整理超长会返回 ASSIST_BODY_TOO_LARGE，原文不被截断。</p></details>
            <details><summary><code>RECOGNITION_REQUEST_INVALID</code><span>图片、模型或预览不符合要求</span><AppIcon name="chevron" :size="16" /></summary><p>核对本人图片、CLEAN 扫描状态、PNG / JPEG / WebP 格式与 5 MiB 上限，以及个人视觉模型配置。真实视频与 DeepSeek 图片路径当前不可用。</p></details>
            <details><summary><code>调用失败或用量未知</code><span>先检查记录，避免重复扣费</span><AppIcon name="chevron" :size="16" /></summary><p>查看使用记录和供应商账户，确认模型权限、余额、地区与端点匹配。图片请求可先刷新本人结果。不要在错误截图、聊天或日志中附密钥、预览令牌或私人原文；向部署者提供脱敏错误码与 traceId 即可。</p></details>
          </div>
        </section>
      </article>

      <aside class="developer-on-page" aria-label="本页目录"><p>ON THIS PAGE</p><a v-for="item in docLinks.slice(1)" :key="item.id" :href="`#${item.id}`" :class="{ active: activeSection === item.id }" :aria-current="activeSection === item.id ? 'location' : undefined">{{ item.label }}</a><span>最后更新 · 2026-10-02 · 个人 BYOK 版本</span></aside>
    </div>
  </div>
</template>

<style scoped>
.docs-example-context > p { margin: 14px 21px; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.docs-code-window > header { flex-wrap: wrap; gap: 8px; }
.docs-code-tabs { flex-wrap: wrap; }
.docs-code-tabs button { min-height: 44px; }
</style>
