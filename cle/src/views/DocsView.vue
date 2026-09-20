<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import AppLogo from '@/components/AppLogo.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'

type Language = 'powershell' | 'curl' | 'java'
const language = ref<Language>('powershell')
const copied = ref(false)
const activeSection = ref('quickstart')
const search = ref('')
const searchInput = ref<HTMLInputElement | null>(null)
const docsStyle = ref<Record<string, string>>({ '--docs-rx': '0deg', '--docs-ry': '0deg' })
let observer: IntersectionObserver | null = null
let scrollFrame = 0

const docLinks = [
  { id: 'quickstart', label: '快速开始' }, { id: 'requirements', label: '环境与依赖' },
  { id: 'architecture', label: '服务架构' }, { id: 'first-request', label: '第一次调用' },
  { id: 'analysis-output', label: '模型分析结果' }, { id: 'quality-route', label: '图像与视频优化' },
  { id: 'model-contract', label: '三家模型接入' }, { id: 'training-data', label: '训练数据准备' },
  { id: 'permissions', label: '权限、配额与密钥' }, { id: 'errors', label: '错误排查' },
]
const searchMatches = computed(() => !search.value.trim() ? [] : docLinks.filter(item => item.label.toLowerCase().includes(search.value.trim().toLowerCase())))

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
  observer = new IntersectionObserver(entries => {
    const visible = entries.filter(entry => entry.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)
    if (visible[0]?.target.id) activeSection.value = visible[0].target.id
  }, { rootMargin: '-18% 0px -66% 0px', threshold: [0, 0.1, 0.5] })
  docLinks.forEach(item => { const node = document.getElementById(item.id); if (node) observer?.observe(node) })
  window.addEventListener('keydown', keyboard)
  window.addEventListener('scroll', syncActiveSection, { passive: true })
  syncActiveSection()
})
onBeforeUnmount(() => {
  observer?.disconnect()
  window.removeEventListener('keydown', keyboard)
  window.removeEventListener('scroll', syncActiveSection)
  if (scrollFrame) window.cancelAnimationFrame(scrollFrame)
})

const snippets: Record<Language, string> = {
  powershell: `$login = Invoke-RestMethod -Method Post \\
  -Uri "http://localhost:8080/api/v1/auth/login" \\
  -ContentType "application/json" \\
  -Body '{"username":"admin","password":"<你的密码>"}'

$headers = @{ Authorization = "Bearer $($login.data.token)" }

# Windows 自带 curl.exe，可可靠上传 multipart 文件
$fileJson = curl.exe -s -X POST \`
  -H "Authorization: $($headers.Authorization)" \`
  -F "file=@D:\\samples\\plate.jpg" \`
  http://localhost:8080/api/v1/files | ConvertFrom-Json

$body = @{
  fileId = $fileJson.data.id
  modelId = 1
  taskType = "LICENSE_PLATE"
  enhancementEnabled = $true
} | ConvertTo-Json

$task = Invoke-RestMethod -Method Post \\
  -Uri "http://localhost:8080/api/v1/inference/tasks" \\
  -Headers $headers -ContentType "application/json" -Body $body

do {
  Start-Sleep -Seconds 1
  $task = Invoke-RestMethod -Uri ("http://localhost:8080/api/v1/inference/tasks/" + $task.data.id) -Headers $headers
} while ($task.data.status -in @("PENDING", "RUNNING"))

$task.data`,
  curl: `TOKEN=$(curl -s http://localhost:8080/api/v1/auth/login \\
  -H 'Content-Type: application/json' \\
  -d '{"username":"admin","password":"<password>"}' \\
  | jq -r '.data.token')

FILE_ID=$(curl -s http://localhost:8080/api/v1/files \\
  -H "Authorization: Bearer $TOKEN" \\
  -F 'file=@./samples/plate.jpg' | jq -r '.data.id')

TASK_ID=$(curl -s http://localhost:8080/api/v1/inference/tasks \\
  -H "Authorization: Bearer $TOKEN" \\
  -H 'Content-Type: application/json' \\
  -d "{\"fileId\":\"$FILE_ID\",\"modelId\":1,
       \"taskType\":\"LICENSE_PLATE\",\"enhancementEnabled\":true}" | jq -r '.data.id')

while :; do
  TASK=$(curl -s "http://localhost:8080/api/v1/inference/tasks/$TASK_ID" \\
    -H "Authorization: Bearer $TOKEN")
  STATUS=$(printf '%s' "$TASK" | jq -r '.data.status')
  [[ "$STATUS" != "PENDING" && "$STATUS" != "RUNNING" ]] && break
  sleep 1
done
printf '%s\\n' "$TASK" | jq`,
  java: `var login = HttpRequest.newBuilder(URI.create(base + "/auth/login"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(
        "{\\\"username\\\":\\\"admin\\\",\\\"password\\\":\\\"<password>\\\"}"))
    .build();

// 上传文件后，使用响应中的 fileId 和模型列表中的 modelId
var requestJson = """
    {"fileId":"%s","modelId":%d,
     "taskType":"LICENSE_PLATE","enhancementEnabled":true}
    """.formatted(fileId, modelId);

var run = HttpRequest.newBuilder(URI.create(base + "/inference/tasks"))
    .header("Authorization", "Bearer " + token)
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
    .build();

// 创建接口返回 PENDING；读取 data.id 后轮询 GET /inference/tasks/{id}
// 直到状态为 COMPLETED 或 FAILED。`,
}

const activeCode = computed(() => snippets[language.value])

async function copyCode(value = activeCode.value) {
  try {
    await navigator.clipboard.writeText(value)
    copied.value = true
    toastStore.success('代码已复制')
    window.setTimeout(() => { copied.value = false }, 1600)
  } catch { toastStore.error('复制失败，请手动选择代码') }
}

const deepSeekEnv = `MODEL_MODE=live
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_API_KEYS=<key-1,key-2>
KIMI_API_KEYS=<key-1,key-2>
QWEN_API_KEYS=<key-1,key-2>
DEEPSEEK_MODEL=deepseek-v4-flash-vision-exp
KIMI_MODEL=kimi-k3
QWEN_MODEL=qwen3-vl-plus
QWEN_VIDEO_MODEL=qwen3.5-omni-plus`

const analysisExample = `{
  "adapter": "DEEPSEEK_API",
  "confidence": 0.91,
  "confidenceSource": "MODEL_SELF_ASSESSMENT_NOT_CALIBRATED",
  "prediction": {
    "text": "苏C7R82Q",
    "fields": { "plateNumber": "苏C7R82Q" }
  },
  "quality": {
    "score": 0.71,
    "bucket": "MEDIUM",
    "labels": ["LOW_LIGHT", "GLARE"],
    "degradation": { "lowLight": 0.72, "blur": 0.31 }
  },
  "analysis": {
    "summary": "车牌区域可见，右侧存在反光。",
    "evidence": ["字符边缘基本连续"],
    "uncertainties": ["末位字符受高光影响"]
  }
}`
</script>

<template>
  <div class="docs-public-shell">
    <header class="docs-public-nav"><RouterLink to="/"><AppLogo /></RouterLink><nav><RouterLink to="/">首页</RouterLink><RouterLink to="/models">模型</RouterLink><a href="#quickstart">文档</a></nav><div><span v-if="authStore.state.user" class="pp-current-user"><i>{{ authStore.state.user.displayName.slice(0, 1) }}</i>{{ authStore.state.user.displayName }}</span><RouterLink v-else to="/login">登录</RouterLink><RouterLink :to="authStore.state.user ? '/app/home' : '/login'" class="pp-enter-button">进入平台 <AppIcon name="arrow" :size="15" /></RouterLink></div></header>
  <div class="developer-docs" :style="docsStyle" @mousemove="moveDocs">
    <aside class="developer-sidebar">
      <label class="docs-search"><AppIcon name="search" :size="16" /><input ref="searchInput" v-model="search" placeholder="搜索文档" @keydown.enter="openFirstSearch" /><kbd>⌘ K</kbd></label>
      <div v-if="searchMatches.length" class="docs-search-results"><a v-for="item in searchMatches" :key="item.id" :href="`#${item.id}`" @click="search = ''">{{ item.label }}</a></div>
      <nav>
        <div><p>GET STARTED</p><a :class="{ active: activeSection === 'quickstart' }" href="#quickstart">快速开始</a><a :class="{ active: activeSection === 'requirements' }" href="#requirements">环境与依赖</a><a :class="{ active: activeSection === 'architecture' }" href="#architecture">服务架构</a><a :class="{ active: activeSection === 'first-request' }" href="#first-request">第一次调用</a></div>
        <div><p>CORE CONCEPTS</p><a :class="{ active: activeSection === 'analysis-output' }" href="#analysis-output">模型分析结果</a><a :class="{ active: activeSection === 'quality-route' }" href="#quality-route">图像与视频优化</a><a :class="{ active: activeSection === 'model-contract' }" href="#model-contract">三家模型接入</a></div>
        <div><p>BUILD</p><a :class="{ active: activeSection === 'training-data' }" href="#training-data">训练数据准备</a><a :class="{ active: activeSection === 'permissions' }" href="#permissions">权限、配额与密钥</a><a :class="{ active: activeSection === 'errors' }" href="#errors">错误排查</a></div>
      </nav>
      <a class="docs-api-link" href="http://localhost:8080/swagger-ui.html" target="_blank" rel="noreferrer"><AppIcon name="terminal" :size="17" /> API reference <AppIcon name="arrow" :size="14" /></a>
    </aside>

    <article class="developer-article">
      <header id="quickstart" class="developer-hero">
        <p class="docs-breadcrumb">API docs <span>/</span> Quickstart</p>
        <h1>开始使用<br />Personal Platform API</h1>
        <p>从环境依赖到异步任务，完成一次图像或视频调用，并读取结构化识别、质量与退化分析结果。</p>
        <div class="developer-hero-actions"><a href="#requirements">开始配置 <AppIcon name="arrow" :size="16" /></a><a href="http://localhost:8080/swagger-ui.html" target="_blank" rel="noreferrer">打开 API Reference</a></div>
      </header>

      <section id="requirements" class="developer-section">
        <div class="developer-step-title"><span>1</span><div><p>SET UP</p><h2>启动本地平台</h2></div></div>
        <p>Windows 上双击 <code>scripts\start-local.bat</code>。脚本会检查并启动 Docker Desktop、保留已有数据，然后构建全部服务。DeepSeek、Kimi 与千问密钥都只写入本机 <code>.env</code>。</p>
        <div class="docs-callout docs-callout--warning"><AppIcon name="shield" :size="20" /><div><strong>Docker 引擎必须处于运行状态</strong><p>出现 <code>npipe:////./pipe/docker_engine</code> 说明 Docker Desktop 引擎未启动。新版脚本会尝试启动并等待 180 秒。</p></div></div>
        <div class="requirements-grid"><article><AppIcon name="terminal" :size="21" /><strong>Docker Desktop</strong><span>Linux containers · Compose v2 · 建议 8 GB 内存</span></article><article><AppIcon name="key" :size="21" /><strong>模型 API Key</strong><span>至少配置 DeepSeek、Kimi 或千问中的一家；支持逗号分隔密钥环</span></article><article><AppIcon name="images" :size="21" /><strong>测试媒体</strong><span>JPEG · PNG · WEBP · MP4 · WEBM ≤ 20 MB；千问内联视频建议 ≤ 7 MB</span></article></div>
      </section>

      <section id="architecture" class="developer-section">
        <div class="developer-step-title"><span>2</span><div><p>ARCHITECTURE</p><h2>了解本地服务依赖</h2></div></div>
        <p>API 请求不会直接等待模型。Java 先记录任务并写入 Redis 队列，独立 <code>model-worker</code> 消费后调用模型；因此 Worker 可单独扩容。</p>
        <div class="docs-service-grid"><article><b>MySQL 8.4</b><span>用户、任务、钱包与审计</span><code>:3306</code></article><article><b>Redis 7.4</b><span>消息队列与分钟级限流</span><code>:6379</code></article><article><b>MinIO</b><span>S3 兼容对象存储</span><code>:9000 / :9001</code></article><article><b>ClamAV</b><span>写入对象存储前流式扫描</span><code>:3310</code></article><article><b>model-worker</b><span>模型调用与 FFmpeg 降噪</span><code>scale independently</code></article><article><b>Vue + Nginx</b><span>公开站点与登录控制台</span><code>:4173</code></article></div>
        <div class="docs-code-window docs-code-window--light"><header><span>独立扩容模型 Worker</span><button class="docs-copy" @click="copyCode('docker compose up -d --scale model-worker=3')"><AppIcon name="copy" :size="15" />复制</button></header><pre><code>docker compose up -d --scale model-worker=3</code></pre></div>
      </section>

      <section id="first-request" class="developer-section">
        <div class="developer-step-title"><span>3</span><div><p>MAKE A REQUEST</p><h2>运行第一次多模态分析</h2></div></div>
        <p>先登录取得平台 JWT，再上传媒体并创建推理任务。创建接口立即返回 <code>PENDING</code>，客户端轮询详情直到 <code>COMPLETED</code> 或 <code>FAILED</code>。</p>
        <div class="docs-code-window">
          <header><div class="docs-code-tabs"><button :class="{ active: language === 'powershell' }" @click="language = 'powershell'">PowerShell</button><button :class="{ active: language === 'curl' }" @click="language = 'curl'">curl</button><button :class="{ active: language === 'java' }" @click="language = 'java'">Java</button></div><button class="docs-copy" @click="copyCode()"><AppIcon :name="copied ? 'check' : 'copy'" :size="15" />{{ copied ? '已复制' : '复制' }}</button></header>
          <pre><code>{{ activeCode }}</code></pre>
        </div>
        <div class="docs-callout"><AppIcon name="spark" :size="20" /><div><strong>一次任务可能产生两次模型请求</strong><p>图片使用固定对比度处理；视频使用 FFmpeg 高/低通、频谱降噪与响度归一化。基线和优化共用模型版本与 trace_id。</p></div></div>
      </section>

      <section id="analysis-output" class="developer-section">
        <div class="developer-step-title"><span>4</span><div><p>READ THE RESULT</p><h2>理解模型分析输出</h2></div></div>
        <p>响应不仅包含识别文本，还包含输入质量、退化强度、证据、不确定性、适配器和 token 用量。</p>
        <div class="docs-code-window docs-code-window--light"><header><span>response.data.optimizedResult</span><button class="docs-copy" @click="copyCode(analysisExample)"><AppIcon name="copy" :size="15" />复制</button></header><pre><code>{{ analysisExample }}</code></pre></div>
        <div class="docs-field-table"><div><strong>prediction</strong><span>任务输出与结构化字段</span><code>object</code></div><div><strong>quality</strong><span>质量分、风险桶与退化维度</span><code>object</code></div><div><strong>analysis</strong><span>摘要、证据与不确定性</span><code>object</code></div><div><strong>confidenceSource</strong><span>标记置信度是否经过统计校准</span><code>string</code></div><div><strong>usage</strong><span>供应商返回的 token 用量</span><code>object</code></div></div>
      </section>

      <section id="quality-route" class="developer-section">
        <div class="developer-step-title"><span>5</span><div><p>COMPARE</p><h2>图像增强与视频杂音处理</h2></div></div>
        <p>图片生成固定对比度版本；视频保留画面流，并在存在音轨时执行高/低通、FFT 降噪与响度归一化。Kimi 使用 Files API 理解视频，千问视频会路由到 Qwen3.5-Omni，使处理后的语音与音效真正参与分析。</p>
        <div class="docs-callout docs-callout--warning"><AppIcon name="shield" :size="20" /><div><strong>降噪是固定预处理，不是准确率承诺</strong><p>无音轨视频仍可分析画面；含音轨视频会生成独立 MP4。是否提高正确率必须用人工真值、固定测试集和统计指标验证。</p></div></div>
        <ol class="developer-flow"><li><span>01</span><div><strong>原始媒体</strong><p>记录 SHA-256，运行基线分析</p></div></li><li><span>02</span><div><strong>固定处理</strong><p>图片增强或视频音轨降噪，生成独立文件和哈希</p></div></li><li><span>03</span><div><strong>同模型复跑</strong><p>保持模型与任务类型一致</p></div></li><li><span>04</span><div><strong>归档差异</strong><p>保存指标、JSON 和 trace_id</p></div></li></ol>
      </section>

      <section id="model-contract" class="developer-section">
        <div class="developer-step-title"><span>6</span><div><p>MODEL RUNTIME</p><h2>配置 DeepSeek、Kimi 与千问</h2></div></div>
        <p>三个适配器都使用 OpenAI 兼容 Chat Completions。DeepSeek 用于图片；Kimi K3 通过 Files API 上传视频；千问图片走 Qwen3-VL，音视频走能理解语音和音效的 Qwen3.5-Omni。逗号分隔的多个 Key 会轮询使用，并在 401/429 后切换。</p>
        <div class="docs-code-window"><header><span>.env · server only</span><button class="docs-copy" @click="copyCode(deepSeekEnv)"><AppIcon name="copy" :size="15" />复制</button></header><pre><code>{{ deepSeekEnv }}</code></pre></div>
        <div class="docs-callout docs-callout--danger"><AppIcon name="key" :size="20" /><div><strong>不要把 API Key 写进 Vue</strong><p><code>VITE_*</code> 变量会进入浏览器构建产物。密钥只能通过后端环境变量注入，并在泄露后立即轮换。</p></div></div>
      </section>

      <section id="training-data" class="developer-section">
        <div class="developer-step-title"><span>7</span><div><p>PREPARE DATA</p><h2>训练与微调准备</h2></div></div>
        <p><code>GET /api/v1/training/dataset</code> 会导出 ZIP：图片/视频、<code>manifest.jsonl</code>、稳定的数据集划分以及 <code>dataset-card.json</code>。模型生成标签会被标记为 <code>UNVERIFIED_TEACHER_LABEL</code>。</p>
        <div class="training-boundary"><div><AppIcon name="check" :size="18" /><span><strong>已经实现</strong>真实推理、教师标签、数据集导出、验证集划分</span></div><div><span>—</span><span><strong>供应商未提供</strong>DeepSeek API 训练任务、微调 job、checkpoint 管理</span></div></div>
        <p>真正训练前必须人工复核标签，并接入具有可训练权重的本地模型或后续供应商端点。页面不会把“导出数据”伪装成“已经微调”。</p>
      </section>

      <section id="permissions" class="developer-section">
        <div class="developer-step-title"><span>8</span><div><p>SECURITY</p><h2>权限、配额与密钥边界</h2></div></div>
        <div class="docs-field-table"><div><strong>管理员</strong><span>用户、角色、全部文件和全部实验</span><code>ADMIN</code></div><div><strong>研究员</strong><span>自己的上传、模型调用、报告和数据集</span><code>RESEARCHER</code></div><div><strong>查看者</strong><span>只读查看已授权资产</span><code>VIEWER</code></div></div>
        <p>菜单隐藏不是安全机制。文件、实验、账单和报告由 Spring Security 再次校验；Redis 执行用户/IP 限流，钱包余额和月配额在入队前校验。管理员可创建自定义平台角色；工作空间再叠加 OWNER / ADMIN / MEMBER / VIEWER 与内容、成员、设置权限。</p>
        <div class="docs-callout"><AppIcon name="mail" :size="20" /><div><strong>支付、工作空间和站内信也是后端权限域</strong><p>扫码令牌只存 SHA-256 且 15 分钟过期；短信验证码最多错误 5 次。站内信附件会先经文件类型、配额与 ClamAV 检查，再进入 MinIO。</p></div></div>
      </section>

      <section id="errors" class="developer-section">
        <div class="developer-step-title"><span>9</span><div><p>TROUBLESHOOT</p><h2>常见错误</h2></div></div>
        <div class="error-guide"><details open><summary><code>INTERNAL_ERROR</code><span>页面要求使用 traceId 排查</span><AppIcon name="chevron" :size="16" /></summary><p>双击 <code>scripts\diagnose-trace.bat</code> 并粘贴页面编号。脚本会同时检索 backend 与 model-worker；若旧日志里没有该编号，先运行最新版启动脚本，复现后再查。</p></details><details><summary><code>MODEL_API_KEY_MISSING</code><span>所选模型的密钥未配置</span><AppIcon name="chevron" :size="16" /></summary><p>重新运行启动脚本，或在本机 <code>.env</code> 中设置对应的 <code>DEEPSEEK_API_KEYS</code>、<code>KIMI_API_KEYS</code> 或 <code>QWEN_API_KEYS</code> 后重启 backend 与 model-worker。</p></details><details><summary><code>MODEL_API_KEY_REJECTED</code><span>密钥无效或已撤销</span><AppIcon name="chevron" :size="16" /></summary><p>在对应供应商控制台轮换密钥；不要把新密钥贴到源码、截图或提交记录中。</p></details><details><summary><code>MODEL_RESPONSE_INVALID</code><span>结构化结果无法解析</span><AppIcon name="chevron" :size="16" /></summary><p>使用 Logs 中的 trace_id 定位请求；重试后仍出现时检查供应商响应格式变更。</p></details><details><summary><code>QWEN_VIDEO_INLINE_LIMIT</code><span>千问视频过大</span><AppIcon name="chevron" :size="16" /></summary><p>压缩为约 7 MB 以内的短视频，或选择通过 Files API 上传视频的 Kimi 模型。</p></details></div>
      </section>
    </article>

    <aside class="developer-on-page"><p>ON THIS PAGE</p><a v-for="item in docLinks.slice(1)" :key="item.id" :href="`#${item.id}`" :class="{ active: activeSection === item.id }">{{ item.label }}</a><span>最后更新 · 2026-08-30 · r6</span></aside>
  </div>
  </div>
</template>
