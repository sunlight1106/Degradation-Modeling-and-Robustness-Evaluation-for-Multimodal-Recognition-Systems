<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import DocRow from '@/components/DocRow.vue'
import CodeListing from '@/components/CodeListing.vue'
import AnnotatedExample from '@/components/AnnotatedExample.vue'
import { toastStore } from '@/stores/toast'
import { authStore } from '@/stores/auth'

type Language = 'powershell' | 'curl' | 'java'
const language = ref<Language>('powershell')
const copied = ref(false)
const docLinks = [
  { id: 'quickstart', label: '快速开始' }, { id: 'reading-guide', label: '逐行实验笔记' }, { id: 'requirements', label: '环境与依赖' },
  { id: 'architecture', label: '服务架构' }, { id: 'first-request', label: '第一次调用' },
  { id: 'analysis-output', label: '模型分析结果' }, { id: 'quality-route', label: '图像与视频优化' },
  { id: 'model-contract', label: '三家模型接入' }, { id: 'knowledge-notes', label: '知识库与笔记' },
  { id: 'training-data', label: '训练数据准备' },
  { id: 'permissions', label: '权限、配额与密钥' }, { id: 'errors', label: '错误排查' },
]
onMounted(() => { void authStore.ensureUser() })

const snippets: Record<Language, string> = {
  powershell: `$login = Invoke-RestMethod -Method Post \`
  -Uri "http://localhost:4173/api/v1/auth/login" \`
  -ContentType "application/json" \`
  -Body '{"username":"admin","password":"<你的密码>"}'

$headers = @{ Authorization = "Bearer $($login.data.token)" }

# Windows 自带 curl.exe，可可靠上传 multipart 文件
$fileJson = curl.exe -s -X POST \`
  -H "Authorization: $($headers.Authorization)" \`
  -F "file=@D:\\samples\\plate.jpg" \`
  http://localhost:4173/api/v1/files | ConvertFrom-Json

$body = @{
  fileId = $fileJson.data.id
  modelId = 1
  taskType = "LICENSE_PLATE"
  enhancementEnabled = $true
} | ConvertTo-Json

$task = Invoke-RestMethod -Method Post \`
  -Uri "http://localhost:4173/api/v1/inference/tasks" \\
  -Headers $headers -ContentType "application/json" -Body $body

do {
  Start-Sleep -Seconds 1
  $task = Invoke-RestMethod -Uri ("http://localhost:4173/api/v1/inference/tasks/" + $task.data.id) -Headers $headers
} while ($task.data.status -in @("PENDING", "RUNNING"))

$task.data`,
  curl: `TOKEN=$(curl -s http://localhost:4173/api/v1/auth/login \\
  -H 'Content-Type: application/json' \\
  -d '{"username":"admin","password":"<password>"}' \\
  | jq -r '.data.token')

FILE_ID=$(curl -s http://localhost:4173/api/v1/files \\
  -H "Authorization: Bearer $TOKEN" \\
  -F 'file=@./samples/plate.jpg' | jq -r '.data.id')

TASK_ID=$(curl -s http://localhost:4173/api/v1/inference/tasks \\
  -H "Authorization: Bearer $TOKEN" \\
  -H 'Content-Type: application/json' \\
  -d "{\"fileId\":\"$FILE_ID\",\"modelId\":1,
       \"taskType\":\"LICENSE_PLATE\",\"enhancementEnabled\":true}" | jq -r '.data.id')

while :; do
  TASK=$(curl -s "http://localhost:4173/api/v1/inference/tasks/$TASK_ID" \\
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
  <div class="lab-docs">
    <header class="lab-header"><nav aria-label="面包屑"><RouterLink to="/">HOME</RouterLink><span>›</span><a href="#quickstart">DOCS</a><span>›</span></nav><div class="lab-header-links"><RouterLink to="/models">模型目录</RouterLink><RouterLink :to="authStore.state.user ? '/app/home' : '/login'">{{ authStore.state.user ? '工作台' : '登录' }}</RouterLink><a href="/swagger-ui.html" target="_blank" rel="noreferrer">API reference ↗</a></div><details class="lab-contents"><summary>目录</summary><nav><a v-for="item in docLinks" :key="item.id" :href="`#${item.id}`">{{ item.label }}</a></nav></details></header>
    <main>
      <DocRow id="quickstart" title="多模态识别平台 · 使用文档" intro><p>在这里了解如何组织素材、调用模型、对照实验结果，以及保存研究记录。</p><p>说明和示例逐段对齐。点击变量名可定位定义，点击段落旁的锚点可直接引用这一节。</p><p><a href="#requirements">开始配置</a> · <a href="#first-request">第一次调用</a> · <a href="#reading-guide">逐行阅读示例</a></p><template #detail><CodeListing id="intro-code" code="# 本地启动
docker compose up -d --wait" /></template></DocRow>
      <DocRow id="reading-guide" title="逐行实验笔记"><p>下面用一份实验配置说明输入和参数的关系。示例不会执行模型调用。</p><template #detail><p class="lab-margin-note">JavaScript / 配置示例</p></template></DocRow>
      <AnnotatedExample />
<DocRow id="requirements" title="启动本地平台"><p>Windows 上运行 <code>deploy.ps1</code>，Linux / macOS 运行 <code>deploy.sh</code>。脚本会检测 Docker、生成含随机密钥的 <code>.env</code>，然后构建并等待全部服务健康。DeepSeek、Kimi 与千问密钥只写入本机 <code>.env</code>，不进版本控制。</p>
<div class="docs-callout docs-callout--warning"><AppIcon name="shield" :size="20" /><div><strong>Docker 引擎必须处于运行状态</strong><p>出现 <code>npipe:////./pipe/docker_engine</code> 说明 Docker Desktop 引擎未启动。新版脚本会尝试启动并等待 180 秒。</p></div></div><template #detail><div class="requirements-grid"><article><AppIcon name="terminal" :size="21" /><strong>Docker Desktop</strong><span>Linux containers · Compose v2 · 建议 8 GB 内存</span></article><article><AppIcon name="key" :size="21" /><strong>模型 API Key</strong><span>至少配置 DeepSeek、Kimi 或千问中的一家；支持逗号分隔密钥环</span></article><article><AppIcon name="images" :size="21" /><strong>测试媒体</strong><span>JPEG · PNG · WEBP · MP4 · WEBM ≤ 20 MB；千问内联视频建议 ≤ 7 MB</span></article></div></template></DocRow>
<DocRow id="architecture" title="了解本地服务依赖"><p>API 请求不会直接等待模型。Java 先记录任务并写入 Redis 队列，独立 <code>model-worker</code> 消费后调用模型；因此 Worker 可单独扩容。</p><template #detail><div class="docs-service-grid"><article><b>MySQL 8.4</b><span>用户、任务、钱包与审计</span><code>容器内网 :3306</code></article><article><b>Redis 7.4</b><span>消息队列与分钟级限流</span><code>容器内网 :6379</code></article><article><b>MinIO</b><span>S3 兼容对象存储</span><code>容器内网 :9000</code></article><article><b>ClamAV</b><span>写入对象存储前流式扫描</span><code>容器内网 :3310</code></article><article><b>model-worker</b><span>模型调用与 FFmpeg 降噪</span><code>scale independently</code></article><article><b>Vue + Nginx</b><span>公开站点与登录控制台，唯一对外入口</span><code>:4173</code></article></div>
<div class="docs-code-window docs-code-window--light"><header><span>独立扩容模型 Worker</span><button class="docs-copy" @click="copyCode('docker compose up -d --scale model-worker=3')"><AppIcon name="copy" :size="15" />复制</button></header><CodeListing id="worker-code" code="docker compose up -d --scale model-worker=3" /></div></template></DocRow>
<DocRow id="first-request" title="运行第一次多模态分析"><p>先登录取得平台 JWT，再上传媒体并创建推理任务。创建接口立即返回 <code>PENDING</code>，客户端轮询详情直到 <code>COMPLETED</code> 或 <code>FAILED</code>。</p>
<div class="docs-callout"><AppIcon name="spark" :size="20" /><div><strong>一次任务可能产生两次模型请求</strong><p>图片使用固定对比度处理；视频使用 FFmpeg 高/低通、频谱降噪与响度归一化。基线和优化共用模型版本与 trace_id。</p></div></div><template #detail><div class="docs-code-window">
          <header><div class="docs-code-tabs"><button :class="{ active: language === 'powershell' }" @click="language = 'powershell'">PowerShell</button><button :class="{ active: language === 'curl' }" @click="language = 'curl'">curl</button><button :class="{ active: language === 'java' }" @click="language = 'java'">Java</button></div><button class="docs-copy" @click="copyCode()"><AppIcon :name="copied ? 'check' : 'copy'" :size="15" />{{ copied ? '已复制' : '复制' }}</button></header>
          <CodeListing id="first-request-code" :code="activeCode" />
        </div></template></DocRow>
<DocRow id="analysis-output" title="理解模型分析输出"><p>响应不仅包含识别文本，还包含输入质量、退化强度、证据、不确定性、适配器和 token 用量。</p><template #detail><div class="docs-code-window docs-code-window--light"><header><span>response.data.optimizedResult</span><button class="docs-copy" @click="copyCode(analysisExample)"><AppIcon name="copy" :size="15" />复制</button></header><CodeListing id="analysis-output-code" :code="analysisExample" /></div>
<div class="docs-field-table"><div><strong>prediction</strong><span>任务输出与结构化字段</span><code>object</code></div><div><strong>quality</strong><span>质量分、风险桶与退化维度</span><code>object</code></div><div><strong>analysis</strong><span>摘要、证据与不确定性</span><code>object</code></div><div><strong>confidenceSource</strong><span>标记置信度是否经过统计校准</span><code>string</code></div><div><strong>usage</strong><span>供应商返回的 token 用量</span><code>object</code></div></div></template></DocRow>
<DocRow id="quality-route" title="图像增强与视频杂音处理"><p>图片生成固定对比度版本；视频保留画面流，并在存在音轨时执行高/低通、FFT 降噪与响度归一化。Kimi 使用 Files API 理解视频，千问视频会路由到 Qwen3.5-Omni，使处理后的语音与音效真正参与分析。</p>
<div class="docs-callout docs-callout--warning"><AppIcon name="shield" :size="20" /><div><strong>降噪是固定预处理，不是准确率承诺</strong><p>无音轨视频仍可分析画面；含音轨视频会生成独立 MP4。是否提高正确率必须用人工真值、固定测试集和统计指标验证。</p></div></div><template #detail><ol class="developer-flow"><li><span>01</span><div><strong>原始媒体</strong><p>记录 SHA-256，运行基线分析</p></div></li><li><span>02</span><div><strong>固定处理</strong><p>图片增强或视频音轨降噪，生成独立文件和哈希</p></div></li><li><span>03</span><div><strong>同模型复跑</strong><p>保持模型与任务类型一致</p></div></li><li><span>04</span><div><strong>归档差异</strong><p>保存指标、JSON 和 trace_id</p></div></li></ol></template></DocRow>
<DocRow id="model-contract" title="配置 DeepSeek、Kimi 与千问"><p>三个适配器都使用 OpenAI 兼容 Chat Completions。DeepSeek 用于图片；Kimi K3 通过 Files API 上传视频；千问图片走 Qwen3-VL，音视频走能理解语音和音效的 Qwen3.5-Omni。逗号分隔的多个 Key 会轮询使用，并在 401/429 后切换。</p>
<div class="docs-callout docs-callout--danger"><AppIcon name="key" :size="20" /><div><strong>不要把 API Key 写进 Vue</strong><p><code>VITE_*</code> 变量会进入浏览器构建产物。密钥只能通过后端环境变量注入，并在泄露后立即轮换。</p></div></div><template #detail><div class="docs-code-window"><header><span>.env · server only</span><button class="docs-copy" @click="copyCode(deepSeekEnv)"><AppIcon name="copy" :size="15" />复制</button></header><CodeListing id="model-contract-code" :code="deepSeekEnv" /></div></template></DocRow>
<DocRow id="knowledge-notes" title="知识库与笔记接口"><p>知识库是跨学科主题树：内置生物化学与医学主题，也可自建任意领域。知识卡为 Markdown 正文加标签；笔记可引用已上传媒体、某次推理任务（含 trace_id）或知识卡，作为可复现证据。</p>
<div class="docs-callout"><AppIcon name="spark" :size="20" /><div><strong>AI 整理会如实标注引擎来源</strong><p>配置了模型密钥时调用真实模型；未配置时走本地规则引擎，响应带 <code>engine=LOCAL_RULES</code> 并附说明，不会伪装成模型输出。</p></div></div><template #detail><div class="docs-field-table"><div><strong>GET /api/v1/knowledge/topics</strong><span>主题树，含领域、内置标记与卡片计数</span><code>knowledge:read</code></div><div><strong>GET/POST /api/v1/knowledge/entries</strong><span>知识卡列表与创建，支持主题过滤与关键词搜索</span><code>knowledge:read / write</code></div><div><strong>GET/POST /api/v1/notes</strong><span>笔记列表与创建，支持状态与关键词过滤</span><code>note:read / write</code></div><div><strong>POST /api/v1/notes/{id}/assist</strong><span>摘要、大纲、标签、格式整理四种动作</span><code>note:write</code></div><div><strong>GET /api/v1/notes/{id}/export</strong><span>导出 Markdown / PDF / Word，PDF 内嵌中文字体</span><code>note:read</code></div><div><strong>POST /api/v1/notes/{id}/shares</strong><span>生成平台内只读分享链接，可设有效期并撤销</span><code>note:write</code></div></div></template></DocRow>
<DocRow id="training-data" title="训练与微调准备"><p><code>GET /api/v1/training/dataset</code> 会导出 ZIP：图片/视频、<code>manifest.jsonl</code>、稳定的数据集划分以及 <code>dataset-card.json</code>。模型生成标签会被标记为 <code>UNVERIFIED_TEACHER_LABEL</code>。</p>
<p>真正训练前必须人工复核标签，并接入具有可训练权重的本地模型或后续供应商端点。页面不会把“导出数据”伪装成“已经微调”。</p><template #detail><div class="training-boundary"><div><AppIcon name="check" :size="18" /><span><strong>已经实现</strong>真实推理、教师标签、数据集导出、验证集划分</span></div><div><span>—</span><span><strong>供应商未提供</strong>DeepSeek API 训练任务、微调 job、checkpoint 管理</span></div></div></template></DocRow>
<DocRow id="permissions" title="权限、配额与密钥边界"><p>菜单隐藏不是安全机制。文件、实验、账单和报告由 Spring Security 再次校验；Redis 执行用户/IP 限流，钱包余额和月配额在入队前校验。管理员可创建自定义平台角色；工作空间再叠加 OWNER / ADMIN / MEMBER / VIEWER 与内容、成员、设置权限。</p>
<div class="docs-callout"><AppIcon name="mail" :size="20" /><div><strong>支付、工作空间和站内信也是后端权限域</strong><p>扫码令牌只存 SHA-256 且 15 分钟过期；短信验证码最多错误 5 次。站内信附件会先经文件类型、配额与 ClamAV 检查，再进入 MinIO。</p></div></div><template #detail><div class="docs-field-table"><div><strong>管理员</strong><span>用户、角色、全部文件和全部实验</span><code>ADMIN</code></div><div><strong>研究员</strong><span>自己的上传、模型调用、报告和数据集</span><code>RESEARCHER</code></div><div><strong>查看者</strong><span>只读查看已授权资产</span><code>VIEWER</code></div></div></template></DocRow>
<DocRow id="errors" title="常见错误"><template #detail><div class="error-guide"><details open><summary><code>INTERNAL_ERROR</code><span>页面要求使用 traceId 排查</span><AppIcon name="chevron" :size="16" /></summary><p>运行 <code>docker compose logs --tail 200 backend model-worker | findstr "你的traceId"</code>（Linux 用 grep）。后端会把 traceId、请求方法、路径、异常类型与完整堆栈写入日志；异步任务还会记录任务 ID、模型与供应商。若旧日志里没有该编号，先重新运行部署脚本，复现后再查。</p></details><details><summary><code>MODEL_API_KEY_MISSING</code><span>所选模型的密钥未配置</span><AppIcon name="chevron" :size="16" /></summary><p>重新运行启动脚本，或在本机 <code>.env</code> 中设置对应的 <code>DEEPSEEK_API_KEYS</code>、<code>KIMI_API_KEYS</code> 或 <code>QWEN_API_KEYS</code> 后重启 backend 与 model-worker。</p></details><details><summary><code>MODEL_API_KEY_REJECTED</code><span>密钥无效或已撤销</span><AppIcon name="chevron" :size="16" /></summary><p>在对应供应商控制台轮换密钥；不要把新密钥贴到源码、截图或提交记录中。</p></details><details><summary><code>MODEL_RESPONSE_INVALID</code><span>结构化结果无法解析</span><AppIcon name="chevron" :size="16" /></summary><p>使用 Logs 中的 trace_id 定位请求；重试后仍出现时检查供应商响应格式变更。</p></details><details><summary><code>QWEN_VIDEO_INLINE_LIMIT</code><span>千问视频过大</span><AppIcon name="chevron" :size="16" /></summary><p>压缩为约 7 MB 以内的短视频，或选择通过 Files API 上传视频的 Kimi 模型。</p></details></div></template></DocRow>
    </main><footer class="lab-footer"><RouterLink to="/">Personal Platform</RouterLink><a href="#quickstart">返回顶部 ↑</a></footer>
  </div>
</template>
