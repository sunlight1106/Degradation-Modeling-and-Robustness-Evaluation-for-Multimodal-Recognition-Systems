# 多模态识别鲁棒性评测与个人知识平台

面向"真实退化输入下的识别可靠性"研究场景的自托管全栈平台：上传被压缩、低光、反光、噪声干扰的图片/视频，经病毒扫描与媒体增强后调用多家视觉大模型，记录基线与优化双路的置信度、退化诊断、token 成本与可追溯的 `trace_id`；同时内置跨学科知识库与 Markdown 笔记，可用 AI 辅助整理并导出/分享。

业务数据存 MySQL，任务经 Redis 排队，媒体存 MinIO，上传前由 ClamAV 扫描，模型调用由可独立扩容的 Worker 执行。前端 Vue 3，后端 Spring Boot 3（Java 17），全部容器化，`docker compose` 一键启动。

---

## 快速开始

### 最低环境要求

| 项 | 要求 |
| --- | --- |
| Docker | Docker Desktop（切换到 **Linux containers**），或 Linux Docker Engine + Compose v2 |
| 内存 | 至少为 Docker 分配 **4 GB**（MySQL + Redis + MinIO + ClamAV 同时运行） |
| 磁盘 | 预留 **5 GB**（镜像 + ClamAV 病毒库 + 构建缓存） |
| 网络 | 首次构建需联网：拉取 Maven/npm 依赖与 ClamAV 病毒库 |

> 仅用 Docker 一键部署时，**无需**在本机安装 Java、Node、Maven。它们只在容器内使用。

### 启动命令

```sh
git clone https://github.com/sunlight1106/Degradation-Modeling-and-Robustness-Evaluation-for-Multimodal-Recognition-Systems.git
cd Degradation-Modeling-and-Robustness-Evaluation-for-Multimodal-Recognition-Systems
```

Windows（PowerShell）：

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy.ps1
```

Linux / macOS：

```sh
sh deploy.sh
```

脚本首次运行会：检测 Docker → 生成 `.env`（含随机强密码与密钥）→ `docker compose up -d --build --wait`。等待各服务健康检查通过后即完成。

### 访问与账号

| 用途 | 地址 / 账号 |
| --- | --- |
| 平台首页 | **http://localhost:4173** |
| 管理员 | 用户名 `admin`，密码见本机 `.env` 的 `BOOTSTRAP_ADMIN_PASSWORD`（随机生成，登录后请立即修改） |
| 体验账号 | 用户名 `test`，密码 `Test1234`（供访客试用，权限为研究员，无管理能力） |
| 自助注册 | 首页登录区 → 注册，填写用户名/邮箱/密码即可，管理员后台即时可见 |

端口被占用时：Windows 首次运行传 `-Port 4273`；已部署则改 `.env` 的 `WEB_PORT` 后重跑脚本。

> ⚠️ **不要**手动把空白的 `.env.example` 复制成 `.env`——那样密钥为空会导致后端启动失败。请务必用 `deploy` 脚本生成。

---

## 功能总览

### 识别评测（核心）

- **多模态上传**：JPEG / PNG / WEBP / MP4 / WEBM；文件头校验、SHA-256 去重、ClamAV 病毒扫描、MinIO 对象存储、鉴权下载。
- **退化诊断**：对每张输入给出质量分与退化维度（低照度、模糊、反光、压缩、背景噪声等）。
- **双路对比**：同一模型对"原始输入"和"增强后输入"各跑一次，并排展示置信度、延迟、路由策略差异。
- **异步队列**：API 立即返回 `PENDING`，任务入 Redis 队列，由独立 `model-worker` 消费，可水平扩容。
- **媒体增强**：图片做对比度/锐化增强；视频保留画面流，对音轨执行 `highpass + lowpass + afftdn + loudnorm` 降噪后再次分析（依赖 FFmpeg，已装于镜像内）。
- **可追溯**：每次调用记录模型版本、供应商、输入输出 token、人民币估算成本与 `trace_id`，全链路可查日志。

### 知识库与笔记

- **知识库**：跨学科主题树（内置生物化学与医学 6 主题 18 张知识卡，可自行增删改），知识卡支持 Markdown、标签、与笔记双向链接。
- **笔记**：Markdown 编辑器 + 实时预览；可引用已上传的图片/视频、某次推理任务的输出与 `trace_id` 作为可复现证据。
- **AI 辅助整理**：摘要、大纲、标签、格式润色四种动作。配了模型密钥走真实模型；未配则走本地规则引擎，并在结果中**如实标注** `engine=LOCAL_RULES`，不伪装成模型输出。
- **导出**：Markdown / PDF / Word 三种格式。PDF 与 Word 均正确支持中文（PDF 内嵌 STSong CJK 字体，非 ASCII 不会被替换为 `?`）。
- **分享**：生成平台内只读分享链接，可设有效期、统计浏览次数、随时撤销（撤销后返回 410）。

### 平台治理

- **RBAC 权限**：管理员 / 研究员 / 查看者三种角色，权限码以角色为单位统一管理，管理员可在后台创建用户、改角色、启停账号。
- **计费沙箱**：个人钱包、月度配额、用量账本；支付宝/微信/银行卡**本地充值沙箱**（扫码确认、短信验证码、到账轮询均为演示，不发生真实扣款）。
- **供应商预算**：管理员维护各供应商预算与已用/剩余额度，API 密钥以 AES-256-GCM 加密入库，支持逗号分隔密钥环与 401/429 自动轮换。
- **协作**：GitHub 风格工作空间、成员角色、带病毒扫描附件的站内信箱。

---

## 模型接入

默认 `MODEL_MODE=demo`：返回**明确标注**的合成演示结果（结果体带 `adapter: DEMO`、`warnings: "DEMO 结果不得用于论文结论"`），不产生真实调用费用，**不能**作为准确率或论文结论。

要接入真实模型，编辑 `.env`：

```env
MODEL_MODE=live
DEEPSEEK_API_KEYS=sk-xxx,sk-yyy      # 多个密钥逗号分隔，自动轮换
KIMI_API_KEYS=
QWEN_API_KEYS=
# 模型 ID 改成你账户实际可用的名称
DEEPSEEK_MODEL=deepseek-v4-flash-vision-exp
KIMI_MODEL=kimi-k3
QWEN_MODEL=qwen3-vl-plus
QWEN_VIDEO_MODEL=qwen3.5-omni-plus
```

改完 `docker compose up -d backend model-worker` 重启生效。

| 供应商 | 默认模型 | 图片 | 视频/音频 | 接入方式 |
| --- | --- | --- | --- | --- |
| DeepSeek | `deepseek-v4-flash-vision-exp` | ✅ | ❌ | OpenAI 兼容图片消息 |
| Kimi | `kimi-k3` | ✅ | ✅ | 图片内联；视频先传 Files API 再传 `ms://` ID |
| 千问 | `qwen3-vl-plus` | ✅ | — | OpenAI 兼容图片消息 |
| 千问 | `qwen3.5-omni-plus` | — | ✅ | 音视频消息；Base64 请求约 7 MB 上限 |

自建/本地模型：设 `MODEL_MODE=http` + `MODEL_BASE_URL`，按 `ai/` 目录内适配器实现的 HTTP 契约对接。本仓库**不含任何模型权重**。

---

## 目录结构

| 路径 | 内容 |
| --- | --- |
| `cle/` | Vue 3 客户端：页面、样式、静态资源、Nginx 配置 |
| `src/main/` | Spring Boot API、业务逻辑、鉴权、配置、Flyway 引导 |
| `src/test/` | 登录、权限、上传、实验、报告、媒体处理的集成测试 |
| `ai/src/main/java/` | 模型适配器、密钥轮转、媒体处理、笔记 AI 辅助；由根 Maven 统一编译 |
| `database/migrations/` | Flyway 版本迁移（V1–V6），**唯一的建表来源** |
| `models/` | 模型扩展占位目录；不提交权重 |
| `compose.yaml` | MySQL、Redis、MinIO、ClamAV、API、Worker、客户端的服务编排 |
| `deploy.ps1` / `deploy.sh` | 一键生成 `.env` 并部署 |

实际用户数据保存在 Docker 命名卷（`mysql_data`、`object_data`、`upload_data` 等）中，不写入 Git。**备份数据库和对象存储时务必同时保存 `.env`**——已加密的供应商密钥依赖其中的主密钥才能解密。

---

## 日常运维

```sh
docker compose ps                                  # 查看服务状态
docker compose logs --tail 100 backend model-worker # 查看日志
docker compose stop                                # 停止（保留数据）
docker compose up -d --wait                        # 启动
```

- `docker compose down` 保留数据卷；`docker compose down -v` 会**删除全部数据**，不能用于普通重启。
- 首次 ClamAV 病毒库下载较慢，用 `docker compose logs clamav` 查看进度；网络恢复后重跑部署脚本即可。
- 客户端依赖后端健康检查通过后才启动。

### 网络暴露

默认**只监听本机**，且仅暴露前端端口 `127.0.0.1:${WEB_PORT:-4173}`。数据库（3306）、Redis（6379）、MinIO（9000/9001）、后端（8080）均**不向宿主机暴露**，只在容器内网互通，前端经 Nginx 同源代理 `/api/` 到后端。

> 接口文档（Swagger UI）随后端提供，但默认不暴露到宿主机。需要时用 `docker compose exec backend sh` 进入容器，或临时为 backend 增加端口映射后访问 `http://localhost:8080/swagger-ui.html`。

---

## 本地开发（不走 Docker）

需要 Node.js 22+、JDK 17、Maven 3.9+、FFmpeg（媒体测试依赖）。

```sh
npm ci --prefix cle            # 安装前端依赖
npm run build --prefix cle     # 类型检查 + 构建
mvn verify                     # 后端编译 + 集成测试（含 FFmpeg 媒体处理）
```

前端开发服务器（API 转发到本机 `127.0.0.1:8080`）：

```sh
npm run dev --prefix cle
```

后端可直接 `mvn spring-boot:run`，默认 `demo` 模式、文件系统存储、内联队列，便于离线开发。完整 Docker 模式则通过 Nginx 同源转发，无需开发服务器。

CI（`.github/workflows/verify.yml`）在每次推送时执行前端 `npm ci && npm run build` 与后端 `mvn -B verify`（自动安装 FFmpeg）。

---

## 数据与隐私边界

- 模型成本按供应商返回的 token 与 `.env` 中可配置单价**估算**；"官方余额"仅在供应商提供可查询端点时显示，否则明确标记为不可读取或本地预算。
- 充值是**本地沙箱**：二维码、短信验证码仅演示订单状态与账本入账，不请求真实支付宝/微信/银行，不真实扣款。接入真实支付须使用支付机构托管页、签名回调与正式短信网关。
- 平台已实现真实推理、教师标签、可审查数据集导出与稳定 train/validation/test 划分；但当前供应商适配器**未实现**训练 job、checkpoint 或权重微调，页面会如实显示这些能力不可用，不伪造"一键微调成功"。
- demo 模式的置信度由输入文件哈希确定性生成，基线路与优化路使用不同文件，**不存在**"优化后必然更高"的关系；任何准确率结论都需人工真值与固定评测集。

---

## 安全提醒

- 不要把 API Key 写入 `VITE_*` 环境变量、前端源码、截图或 Git 历史。
- 在聊天等公开位置出现过的密钥，应立即在供应商控制台撤销并重建。
- `.env` 不进入版本控制；生产部署应使用 Secret Manager、TLS、强管理员密码，并收敛 Swagger/Actuator 暴露面。
- 上传限制、ClamAV、限流与配额属于纵深防御，不能替代网络隔离、备份与依赖漏洞管理。
- 体验账号 `test/Test1234` 是**公开共享**账号，仅供功能试用，请勿在其中存放真实数据；正式使用前应在 `.env` 改密或置空 `BOOTSTRAP_TEST_PASSWORD` 以禁用。

---

## 素材与许可

原创代码适用根目录 `LICENSE`（MIT）。`cle/public/art/` 中的弹丸论破相关图片、视频与角色素材为第三方内容，**不属于本项目原创代码，不在 MIT 授权范围内**；来源见 `SOURCE.txt` 与 `home/SOURCES.json`，权利归原作者及权利人所有。本项目与 Spike Chunsoft 无隶属或背书关系。请勿在未获授权的情况下将该目录素材用于任何公开分发场景。
