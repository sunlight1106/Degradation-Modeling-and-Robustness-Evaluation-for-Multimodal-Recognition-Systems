# 多模态识别鲁棒性评测与个人知识平台

自托管的个人知识与识别实验平台：支持 Markdown 笔记、知识库、独立词汇学习和个人账户设置。个人 AI 文本整理与票据/车牌图片识别使用本人加密密钥，逐次预览、确认后调用；远程执行默认关闭。旧图片/视频双路实验仅提供明确标注的 DEMO 合成结果，用于检查上传、扫描、预处理、队列与报告链路，不能作为真实识别结论。

业务数据存 MySQL，旧 DEMO 实验经 Redis 排队，由独立 Worker 执行；个人 AI 使用 API 的独立预览/执行流程。媒体存 MinIO，写入存储前由 ClamAV 扫描。前端 Vue 3，后端 Spring Boot 3（Java 17），全部容器化，`docker compose` 一键启动。

---

## 完整数据库与性能改进（2026-10）

- [完整组件运行、持久化与当前环境验证边界](docs/STACK.md)
- [31 张业务表、V1–V12 迁移与真实 MySQL 契约测试](database/README.md)
- [算法/查询优化、基准方法与复现命令](docs/PERFORMANCE.md)
- [实测结果与验证清单](docs/VALIDATION.md)

新增 Maven Wrapper（`./mvnw` / `mvnw.cmd`）锁定 Maven 3.9.9；完整 Compose 使用真实 MySQL、Redis、ClamAV、MinIO 和独立 Worker。当前受限云环境可验证 MySQL/Redis/ClamAV，但 MinIO 的系统网卡枚举被禁止；不要把显式 filesystem 部分运行模式当作 S3 已验证。

## 快速开始

### 部署方式一：Docker 一键部署（推荐）

**这种方式下你不需要在本机安装 Java、Node、Maven**——它们只在容器内使用。

| 项 | 最低要求 | 说明 |
| --- | --- | --- |
| 操作系统 | Windows 10/11（64 位）、macOS 12+、主流 Linux 发行版 | Windows 需启用 WSL 2 |
| Docker | Docker Desktop 4.x+（切换到 **Linux containers**），或 Linux Docker Engine 24+ 配 Compose v2（`docker compose` 子命令，不是旧版 `docker-compose`） | 用 `docker compose version` 自检，应输出 v2.x |
| 内存 | 为 Docker 分配 **≥ 4 GB** | 7 个容器同时运行：MySQL、Redis、MinIO、ClamAV、API、Worker、前端 |
| 磁盘 | 预留 **≥ 6 GB** 空闲 | 镜像约 2.5 GB + ClamAV 病毒库约 0.3 GB + 构建缓存与数据卷 |
| CPU | 2 核可用 | 首次构建后端（Maven 编译 + 测试）较吃 CPU |
| 网络 | 首次构建需联网 | 拉取 Maven/npm 依赖、基础镜像、ClamAV 病毒库；之后可离线运行 |

启动命令见下一节。

### 部署方式二：不用 Docker，纯本机开发

若你要改代码并直接在本机跑（不用容器），需要装齐下列工具。**版本需与 CI 一致**（`.github/workflows/verify.yml`）：

| 软件 | 版本 | 用途 | 自检命令 |
| --- | --- | --- | --- |
| JDK | **17**（Temurin/Oracle 均可） | 编译与运行 Spring Boot 后端 | `java -version` |
| Maven | **3.9+** | 依赖管理与构建 | `mvn -version` |
| Node.js | **22.22.2+**（22.x LTS） | 前端构建与开发服务器 | `node -v` |
| npm | 随 Node 22 附带（≥ 10） | 前端依赖安装 | `npm -v` |
| FFmpeg | 4.x 或 5.x/6.x/7.x，需在 `PATH` 中 | 视频音轨降噪与媒体测试 | `ffmpeg -version` |
| MySQL | **8.4** | 业务数据库（Docker 方式无需自装） | `mysql --version` |
| Redis | **7.4+** | 任务队列（Docker 方式无需自装） | `redis-server --version` |

> FFmpeg 是硬依赖：后端测试与视频降噪分支都会调用它。缺了它 `mvn verify` 的媒体测试会失败。Windows 可用 `winget install Gyan.FFmpeg` 安装并确认 `ffmpeg` 在 PATH。

### 软件版本清单（仓库实际锁定值）

以下是构建文件里**真实声明**的版本，便于排查兼容性问题：

| 组件 | 版本 | 来源 |
| --- | --- | --- |
| Spring Boot | 3.3.5 | `pom.xml` |
| Java（字节码目标） | 17 | `pom.xml` |
| 后端构建镜像 | `maven:3.9.9-eclipse-temurin-17` | `src/Dockerfile` |
| 后端运行镜像 | `eclipse-temurin:17-jre` | `src/Dockerfile` |
| Vue | 3.5.13 | `cle/package.json` |
| Vite | ^6.4.3 | `cle/package.json` |
| 前端构建镜像 | `node:22-alpine` | `cle/Dockerfile` |
| 前端服务镜像 | `nginx:1.27-alpine` | `cle/Dockerfile` |
| MySQL | 8.4 | `compose.yaml` |
| Redis | 7.4-alpine | `compose.yaml` |
| MinIO | 官方最终修复源版本 2025-10-15，归档停止维护；源码/工具链 SHA-256 锁定 | `scripts/components/Minio.Dockerfile` |
| ClamAV | stable | `compose.yaml` |
| 浏览器 | Chrome / Edge / Firefox / Safari 近两年版本 | 前端未用实验性 API |

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

脚本首次运行会：检测 Docker → 生成 `.env`（含随机管理员密码、数据库密码与密钥）→ `docker compose up -d --build --wait`。等待各服务健康检查通过后即完成。

### 访问与账号

| 用途 | 地址 / 账号 |
| --- | --- |
| 平台首页 | **http://localhost:4173** |
| 管理员 | 用户名 `admin`；首次部署随机生成密码，保存在本机 `.env` 的 `BOOTSTRAP_ADMIN_PASSWORD`，不会打印到部署日志 |
| 体验账号 | 默认不创建；需要演示时在 `.env` 显式配置 `BOOTSTRAP_TEST_PASSWORD`（至少 8 位，勿与管理员相同），权限为研究员 |
| 自助注册 | 首页登录区 → 注册，填写用户名/邮箱/密码即可，管理员后台即时可见 |

端口被占用时：Windows 首次运行传 `-Port 4273`；已部署则改 `.env` 的 `WEB_PORT` 后重跑脚本。

> ⚠️ **不要**手动把空白的 `.env.example` 复制成 `.env`——那样密钥为空会导致后端启动失败。请务必用 `deploy` 脚本生成。

---

## 功能总览

### 个人图片识别与 DEMO 实验

- **个人图片识别**：本人 CLEAN 扫描的 PNG / JPEG / WebP 图片，最多 5 MiB；票据和车牌两种任务。先核对原图、SHA-256、模型、端点和完整指令，再明确确认发送，结果归本人保存。
- **能力边界**：真实视频调用尚未开放；DeepSeek 视觉尚未验证，当前拒绝该路径。其他供应商也需要用户明确选择具备图片能力的模型。协议只做过模拟验证。
- **DEMO 媒体链路**：旧实验支持 JPEG / PNG / WEBP / MP4 / WEBM，包含文件头校验、SHA-256、ClamAV 扫描、对象存储与鉴权下载；其识别、质量和退化分析为合成输出。
- **DEMO 队列恢复**：Logs 支持本人手动重新入队等待中的任务；并发重复交付不重复提交成功结果和用量。详见[故障恢复、限制与验收](docs/QUEUE_RECOVERY.md)。
- **DEMO 双路与队列**：创建任务返回 `PENDING`，Redis 与独立 `model-worker` 完成基线/处理后对照。图片做固定增强，视频音轨使用 FFmpeg 预处理；处理成功不表示识别质量提高。
- **记录与用量**：旧实验保留任务状态、结果与 `trace_id`。个人 AI 另存本人调用记录及供应商已报告的 Tokens；未知用量不会记为零费用，不估算个人 AI 金额或扣平台钱包。

### 知识库与笔记

- **知识库**：跨学科主题树（内置生物化学与医学 6 主题 18 张知识卡，公共卡仅管理员维护，个人卡由本人管理），知识卡支持 Markdown、标签、与笔记双向链接。
- **笔记**：Markdown 编辑器 + 实时预览；可引用已上传的图片/视频、某次推理任务的输出与 `trace_id` 作为可复现证据。
- **个人 AI 辅助整理**：摘要、大纲、标签、格式整理、实验报告草稿；每位用户使用自己的加密 API 配置。先预览确切外发内容，再确认调用，结果预览后手动应用。本地规则为单独选项，明确标注 `LOCAL_RULES`，不会暗中改用管理员密钥。
- **实验材料选择**：只列出本人的已完成实验及个人图片识别结果；服务端在选择、插入预览与 AI 执行时校验归属。
- **导出**：Markdown / PDF / Word 三种格式。PDF 与 Word 均正确支持中文（PDF 内嵌 STSong CJK 字体，非 ASCII 不会被替换为 `?`）。
- **分享**：生成平台内只读分享链接，可设有效期、统计浏览次数、随时撤销（撤销后返回 410）。

### 平台治理

- **RBAC 权限**：管理员 / 研究员 / 查看者三种角色，权限码以角色为单位统一管理，管理员可在后台创建用户、改角色、启停账号。
- **计费沙箱**：个人钱包、月度配额、用量账本；支付宝/微信/银行卡**本地充值沙箱**（扫码确认、短信验证码、到账轮询均为演示，不发生真实扣款）。
- **个人设置**：资料、密码、登录会话、个人 AI、真实平台用量、私有 JSON 导出与账户隔离的外观偏好。密码/角色等安全更改使相关会话失效，旧无会话 JWT 升级后需重新登录。
- **凭据隔离**：个人 API 密钥以 AES-256-GCM 加密入库，只能由本人配置使用，响应不返回密钥。历史管理员密钥记录保留，但个人调用不会读取它们。费用由个人供应商计收，平台不冒充供应商余额/账单。
- **协作**：GitHub 风格工作空间、成员角色、带病毒扫描附件的站内信箱。

---

## 模型接入与个人图片识别

默认 `MODEL_MODE=demo`：旧实验对照入口返回明确标注的合成结果，不产生真实模型费用，不能用于准确率或论文结论。旧 `live` / `http` 公共密钥执行入口已停用。

真实文本整理、票据和车牌图片识别使用独立个人 BYOK 流程：

1. 部署者保管独立的 `CREDENTIAL_MASTER_KEY`，通过 HTTPS 提供应用；用户在「设置 → 个人 AI」配置自己的供应商、模型 ID 和密钥。
2. 部署默认 `PERSONAL_AI_REMOTE_ENABLED=false`，个人 AI 外部执行关闭。准备好后，部署者可以在自己的配置中启用，再重新创建后端容器使配置生效。开发验证没有打开该开关或调用真实供应商。
3. 每次先查看供应商/模型/确切文本；图片识别还展示自己的图片、SHA-256、大小和提示词。确认后才发送，最多一次调用，结果需人工检查。
4. 图片须为通过病毒扫描的 PNG/JPEG/WebP、最多 5 MiB。票据/车牌结果归本人保存，可在笔记选择器插入。真实视频调用尚未开放；DEMO 视频流程仍保留。

文本适配器覆盖 OpenAI、Gemini、xAI/Grok、Claude、DeepSeek、Kimi、Qwen，以及通过 Groq/Together 等真实托管 API 的 Llama 和自定义 OpenAI 兼容网关。分别实现 OpenAI Chat、Anthropic Messages、Gemini generateContent 协议。模型须由用户明确选择，账户可用性与视觉能力不作默认保证；DeepSeek 图片调用尚未获验证，因此当前拒绝该路径。

自定义/区域网关须由部署者加入 `PERSONAL_AI_ALLOWED_BASE_URLS` 的精确 HTTPS 地址列表；拒绝私网、环回、保留地址、重定向与 DNS 重绑定。这里不提供任意 localhost/内网穿透，也不假设存在通用 Meta API。

- [个人 AI 接口、安全默认与官方协议来源](docs/PERSONAL_AI.md)
- [账户、权限与请求安全边界](docs/SECURITY.md)
- [恶意脚本/主动内容检查及兼容限制](docs/CONTENT_SECURITY.md)
- [独立词汇学习：词书、四次答对与次日复习](docs/VOCABULARY.md)

所有供应商协议验证使用模拟服务与合成数据，不代表已验证真实密钥、地区路由、费用或所有模型兼容性。

---

## 目录结构

| 路径 | 内容 |
| --- | --- |
| `cle/` | Vue 3 客户端：页面、样式、静态资源、Nginx 配置 |
| `src/main/` | Spring Boot API、业务逻辑、鉴权、配置、Flyway 引导 |
| `src/test/` | 登录、权限、上传、实验、报告、媒体处理的集成测试 |
| `ai/src/main/java/` | 旧 DEMO 调用、历史共享配置兼容、媒体处理、本地笔记规则；个人 BYOK 实现在 `src/main/`，均由根 Maven 编译 |
| `database/migrations/` | Flyway 版本迁移（V1–V12），**唯一的建表来源** |
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

> 接口文档通过 Nginx 同源代理提供：默认访问 `http://localhost:4173/swagger-ui.html`；OpenAPI 为 `/v3/api-docs`。无需额外开放后端端口。对外部署时由部署者审查并限制文档访问面。

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

完整本地开发请按 [组件栈说明](docs/STACK.md) 准备依赖并使用 `scripts/local/start-stack.sh --build`，由启动器生成私有服务配置、启动数据库和扫描器，并等待就绪。裸 `spring-boot:run` 还需要有效的 `BOOTSTRAP_ADMIN_PASSWORD`、数据库、JWT 和加密主密钥等配置；其默认扫描关闭并不满足个人图片识别的 CLEAN 要求。不要关闭扫描或伪造扫描状态来使用识别。H2 仅用于测试。完整 Docker 模式通过 Nginx 同源转发，无需开发服务器。

CI（`.github/workflows/verify.yml`）执行前端单元测试、挂载 DOM 回归（个人流程、帮助文档、词汇键盘操作）、类型检查和构建、后端 `./mvnw -B verify`（含 FFmpeg），以及真实 MySQL 8.4 迁移、隔离与并发契约测试。DOM 回归不等于浏览器截图或真实供应商验收。

---

## 数据与隐私边界

- 个人 AI 使用本人的供应商额度，不估算金额或读取供应商余额；Token 用量缺失时记为未知，失败/停止等待仍可能产生费用。历史共享配置和本地预算账本不代表个人 BYOK 账单；配置了旧共享密钥的管理员预算查询仍可能访问旧余额接口，个人 AI 的远程开关不控制该历史路径。
- 充值是**本地沙箱**：二维码、短信验证码仅演示订单状态与账本入账，不请求真实支付宝/微信/银行，不真实扣款。接入真实支付须使用支付机构托管页、签名回调与正式短信网关。
- 旧实验可导出数据集和稳定 train/validation/test 划分；DEMO 输出是合成标签，模型标签也需人工复核。当前**未实现**供应商训练 job、checkpoint 或权重微调，导出不代表训练完成。
- DEMO 置信度与双路差值由确定性规则生成，不能当作真实模型表现、准确率提升或论文结论。真实评测仍需要人工真值、固定数据集和独立验证。

---

## 安全提醒

- 不要把 API Key 写入 `VITE_*` 环境变量、前端源码、截图或 Git 历史。
- 在聊天等公开位置出现过的密钥，应立即在供应商控制台撤销并重建。
- `.env` 不进入版本控制；生产部署应使用 Secret Manager、TLS、强管理员密码，并收敛 Swagger/Actuator 暴露面。
- 上传限制、ClamAV、限流与配额属于纵深防御，不能替代网络隔离、备份与依赖漏洞管理。
- 体验账号默认不创建。需要共享演示时请显式配置单独密码，勿存放真实数据。正式使用时保持 `BOOTSTRAP_TEST_PASSWORD` 为空；这只跳过新账号创建，已有体验账号应在管理后台禁用。

---

## 素材与许可

原创代码适用根目录 `LICENSE`（MIT）。`cle/public/art/` 中的弹丸论破相关图片、视频与角色素材为第三方内容，**不属于本项目原创代码，不在 MIT 授权范围内**；来源见 `SOURCE.txt` 与 `home/SOURCES.json`，权利归原作者及权利人所有。本项目与 Spike Chunsoft 无隶属或背书关系。请勿在未获授权的情况下将该目录素材用于任何公开分发场景。
