# Personal Knowledge Base and AI Recognition Evaluation

**个人知识库与 AI 识别评测**

在自己的电脑运行一个研究工作台：写笔记、整理知识、学习词汇，使用自己的 AI 配置识别图片，或运行本地演示实验。

**首次使用按「下载 → 启动 → 登录」操作。Docker 部署不需要在本机安装 Java、Node.js 或 Maven。**

[下载源码 ZIP](https://github.com/sunlight1106/Personal-Knowledge-Base-and-AI-Recognition-Evaluation/archive/refs/heads/main.zip) · [首次启动](#首次启动) · [日常使用](#日常使用) · [更新版本](#更新版本) · [常见问题](#常见问题) · [许可与设计说明](#许可与设计说明)

## 能做什么

| 我想要 | 从哪里开始 | 是否需要模型密钥 |
| --- | --- | --- |
| 写笔记、整理知识 | 我的笔记 / 知识库 | 不需要；可使用本地规则整理 |
| 背单词 | 目录与账户 → 背单词 | 不需要 |
| 建研究群、讨论和分享资料 | 目录与账户 → 群组协作 | 不需要 |
| 联系管理员或同组成员 | 目录与账户 → 站内信箱 | 不需要 |
| 识别票据或车牌图片 | 设置 → 个人 AI，再进入实验台 | 需要自己的视觉模型和部署端远程开关 |
| 体验图片 / 视频双路流程 | 实验台 → DEMO | 不需要；输出为合成演示结果 |
| 下载素材与报告 | 目录与账户 → 下载中心 | 不需要 |
| 下载自己的账户数据 | 设置 → 隐私与数据 | 不需要，需要确认本人密码 |

个人 AI 会先展示发送预览，由你确认后才调用供应商；默认关闭远程执行。DEMO 置信度和识别结果不能用于准确率或论文结论，真实视频模型调用尚未开放。

## 下载项目

群组快速使用：研究员打开「群组协作」→「新建群组」→ 展开「成员与权限」添加账号 → 在本群发言、回复或附上资料。所有登录用户都可以给平台管理员发站内信；同组成员按群内权限私信。具体操作和权限边界见 [群组与站内信指南](docs/GROUPS.md)。

### 直接下载 ZIP

1. 点击上方「下载源码 ZIP」，或在 GitHub 选择 **Code → Download ZIP**。
2. 将 ZIP **完整解压**到固定目录，例如 `D:\KnowledgeAI`。
3. 打开解压后的项目目录，确认能看到 `compose.yaml`、`deploy.ps1` 和 `cle` 文件夹。

ZIP 是源码包，不是安装程序；不能直接双击里面的网页运行。

### 使用 Git，方便以后更新

```sh
git clone --depth 1 https://github.com/sunlight1106/Personal-Knowledge-Base-and-AI-Recognition-Evaluation.git
cd Personal-Knowledge-Base-and-AI-Recognition-Evaluation
```

## 首次启动

### 1. 准备 Docker

Windows / macOS 安装并启动 [Docker Desktop](https://docs.docker.com/desktop/)；Windows 使用 WSL 2 和 **Linux containers**。Linux 安装 Docker Engine 与 Compose 插件。Compose 需要 **2.20.2 或更新版本**。

首次启动需要联网下载镜像、依赖与病毒库。建议为 Docker 分配 8 GB 内存，并预留镜像、构建缓存和数据空间。当前 MinIO 构建目标为 `linux/amd64`；ARM 电脑需要 Docker 的相应仿真能力。

在终端检查：

```sh
docker info
docker compose version
```

### 2. 在项目目录运行脚本

**Windows：**在项目文件夹地址栏输入 `powershell` 并回车，然后执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1
```

**Linux / macOS：**在项目目录打开终端，执行：

```sh
sh deploy.sh
```

脚本生成私有 `.env`、构建镜像并等待服务就绪。看到 `Ready: http://localhost:4173` 后打开该地址。首次构建较慢，以终端显示的完成状态为准。

Windows 脚本会尝试启动 Docker Desktop；仅识别到特定残留通信文件故障时执行一次受限恢复。其他故障会停止并提示检查。Linux / macOS 需先启动 Docker 引擎。

**不要把空白 `.env.example` 直接当作 `.env`，也不要覆盖已有 `.env`。** 脚本自动生成管理员密码、数据库密码与服务密钥；已有配置会复用。

### 3. 登录

| 内容 | 默认值 / 获取方式 |
| --- | --- |
| 平台地址 | [http://localhost:4173](http://localhost:4173)，自定义端口以脚本输出为准 |
| 管理员用户名 | `admin`，对应 `.env` 的 `BOOTSTRAP_ADMIN_USERNAME` |
| 首次管理员密码 | 用文本编辑器打开本机 `.env`，查看 `BOOTSTRAP_ADMIN_PASSWORD=` 后的值 |
| 普通账户 | 登录页选择「注册」 |
| 体验账户 | 新部署默认不创建；需要时由部署者单独配置 |
| 操作指南 | 顶部「操作文档」，地址 `/docs` |
| API 文档 | `/swagger-ui.html`；接口描述 `/v3/api-docs` |

旧部署不会因更新自动更换已有管理员密码。`.env` 包含私密配置，不要上传 GitHub 或截图公开。

4173 被占用时，Windows **首次部署**可指定端口：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1 -Port 4273
```

已有 `.env` 时，修改其中 `WEB_PORT` 后重跑脚本；`-Port` 只用于生成新配置。

## 日常使用

操作文档里的标题和代码变量可以点击定位。高亮随鼠标悬停显示、移开消失；点击或刷新带行号的网址不会保持整行底色。键盘 Tab 仍显示焦点提示。

### 写笔记与导出

1. 从「目录与账户 → 我的笔记」进入，新建笔记，填写标题和正文并保存。
2. 需要整理时，选择「整理与写作 → 本地规则」，检查预览后应用。
3. 在笔记页面导出 Markdown、PDF 或 Word；需要分享时主动创建只读链接，可设置有效期并撤销。

### 运行一次演示实验

1. 打开「实验台」，找到 **DEMO** 区域。
2. 上传 JPEG、PNG、WEBP 图片，或 MP4、WEBM 视频；单文件不超过 20 MB。
3. 选择任务与模型，按需开启优化，点击「运行实验」。
4. 完成后查看对照结果。在「下载中心」切换文件或报告，下载素材和 JSON 报告。

DEMO 用于检查上传、扫描、媒体处理、队列和报告流程。它不调用真实模型，不能证明识别效果提升。

### 使用自己的 AI

1. 打开「设置 → 个人 AI」，填写供应商、账户实际可用的模型 ID、API 地址和自己的密钥，启用并保存。
2. 部署者准备好后，将 `.env` 的 `PERSONAL_AI_REMOTE_ENABLED` 改为 `true`，执行 `docker compose up -d backend model-worker` 使配置生效。
3. 在笔记中整理文字，或使用实验台上方的「个人 AI 图片识别」。
4. 查看完整发送预览，确认后才发送，结果需要人工核对。

图片限本人上传、扫描通过的 PNG / JPEG / WebP，最大 5 MiB。当前不提供真实视频调用；DeepSeek 图片路径尚未验证，暂不开放。协议已做模拟验证，真实模型可用性、费用和地区权限以自己的供应商账户为准。

个人 AI 使用本人供应商额度，与平台沙箱钱包、历史共享预算分开。失败或停止等待仍可能计费；重试前先查看「设置 → 使用情况」。详见 [个人 AI 说明](docs/PERSONAL_AI.md)。

### 背单词与下载个人资料

- **背单词：**保存时区和每日目标，选词书练习。内置 3 本原创入门词书，共 60 词；支持导入和导出自己的词书。详见 [词汇学习指南](docs/VOCABULARY.md)。
- **账户数据：**在「设置 → 隐私与数据」确认密码后下载本人 JSON 副本，不含密码、会话令牌和 AI 密钥。
- **素材与报告：**在「下载中心」下载有权访问的文件和实验报告。个人图片识别结果在实验台查看，也可插入笔记后导出。

## 再次启动与停止

Windows 完成首次部署后，双击 **start.cmd**，复用已有镜像。各系统也可运行：

```sh
docker compose up -d --wait --wait-timeout 600
docker compose ps
```

停止服务并保留数据：

```sh
docker compose stop
```

`docker compose down` 也保留数据卷；**不要在普通停止、重启或更新时使用 `docker compose down -v`，它会删除数据卷。**

## 更新版本

### 已下载过旧名称仓库的用户

仓库现名为 **Personal-Knowledge-Base-and-AI-Recognition-Evaluation**（个人知识库与 AI 识别评测）。在原项目目录执行一次，更新远程地址：

```sh
git remote set-url origin https://github.com/sunlight1106/Personal-Knowledge-Base-and-AI-Recognition-Evaluation.git
git remote -v
```

确认 `origin` 显示上面的新地址，再按下方步骤更新。**不用重新下载项目，也不用改本地文件夹名称。** ZIP 用户直接按本节的 ZIP 更新步骤操作。

### Git 下载的用户

先保存本地代码改动，并备份 `.env`、数据库与上传对象。然后在原项目目录执行：

```sh
git pull --ff-only
```

Windows 重新运行 `deploy.ps1`；Linux / macOS 重新运行 `sh deploy.sh`。源码更新必须重建镜像，`start.cmd` 仅用于日常启动。Git 提示冲突时先处理本地改动，不要强制覆盖。Flyway 会在后端启动时应用新增数据库迁移。

### ZIP 下载的用户

1. 下载最新 ZIP 并解压到临时目录。
2. 停止当前服务，将新版源码覆盖到**原项目目录**。
3. 保留原 `.env`，不要替换为空配置；不要删除 Docker 数据卷。
4. 在原目录重新运行部署脚本，完成后刷新网页。

Compose 项目名固定为 `robust-vision`。同一台电脑上的多个源码目录默认共用这组服务和数据，第二次解压不代表独立测试环境。

这是为已有安装保留的兼容标识，不是项目显示名称。数据库、对象存储桶、Docker 数据卷、登录存储键和备份格式也保留原标识；仅因仓库改名，无需修改 `.env`、重建数据库或搬迁数据。Java 包名 `com.robustvision.platform` 继续兼容现有工具。构建包名称和导出文件中的项目名前缀改为 `pkb-ai-evaluation`。

## 常见问题

| 看到什么 | 下一步 |
| --- | --- |
| 找不到 Docker / Docker 未就绪 | 确认已安装、Linux 引擎已启动，等待 Desktop 就绪后重跑脚本 |
| 网页打不开 | 看 `Ready` 地址并检查 `docker compose ps`；Docker 入口不是开发端口 5173 |
| 首次下载很久或服务未健康 | 查看 `docker compose logs --tail 100 clamav backend`；网络恢复后重跑脚本 |
| 登录失败或 500 | 查看 `docker compose logs --tail 100 backend`；旧账户使用原密码，升级后可能需要重新登录 |
| 能预览，不能发送 AI | 远程执行默认关闭；部署者开启开关并重新创建后端容器后才生效 |
| 没有可选个人模型 | 保存并启用个人 AI 配置，回识别页刷新 |
| 页面还是旧样式 | 确认已经重新构建前端，然后 Ctrl+F5 刷新 |
| 改变已有管理员密码 | 优先在设置中修改；运维重置需配置新密码及 `RESET_ADMIN_PASSWORD=true`，重建后端完成后改回 `false` 并再次重建 |

提供错误信息时，只提供脱敏错误码或 `trace_id`，不要附 `.env`、密钥或私人原文。

## 目录与数据存储

| 目录 / 文件 | 用途 |
| --- | --- |
| `cle/` | Vue 3 客户端；公开静态资源在 `cle/static/` |
| `src/main/` | Spring Boot 后端、账户、权限、个人 AI 和业务接口 |
| `src/test/` | 后端测试 |
| `ai/` | DEMO 适配、本地笔记规则、媒体处理与历史配置兼容 |
| `database/migrations/` | Flyway 迁移，唯一建表来源 |
| `docs/` | 配置、功能、验证与维护文档 |
| `scripts/` | Docker 恢复、组件运行、备份及验证工具 |
| `compose.yaml` | 7 个运行服务的编排 |
| `deploy.ps1` / `deploy.sh` | 首次部署及更新时构建 |
| `start.cmd` | Windows 已部署项目的启动入口 |

`database/` 存结构与工具，不是运行时数据目录。MySQL、Redis、MinIO 和 ClamAV 使用 Docker 命名卷。备份需覆盖数据库、对象存储及原 `.env`，加密密钥依赖原主密钥解密。

[数据库与迁移](database/README.md) · [组件与持久化](docs/STACK.md) · [备份工具与适用范围](scripts/backup/README.md) · [队列恢复](docs/QUEUE_RECOVERY.md)

默认仅监听本机前端地址 `127.0.0.1:4173`，其余服务不开放宿主机端口。浏览器经 Nginx 同源代理访问后端。向其他设备提供服务需另行配置网络、HTTPS 与访问控制。

## 开发与验证

前端使用 Node.js 22.22.2+（22.x），后端使用 JDK 17、Maven Wrapper 和 FFmpeg。数据库与其他依赖按 [组件栈说明](docs/STACK.md) 准备；只启动前端不能完成登录。

```sh
npm ci --prefix cle
npm run test:unit --prefix cle
npm run test:ui --prefix cle
npm run build --prefix cle
```

后端：Linux / macOS 使用 `./mvnw -B verify`，Windows 使用 `.\mvnw.cmd -B verify`。完整本地开发见组件栈文档；测试用 H2 不用于正式数据库。

[GitHub Actions](https://github.com/sunlight1106/Personal-Knowledge-Base-and-AI-Recognition-Evaluation/actions) 在推送后执行前端测试与构建、后端测试和 MySQL 契约测试。自动化测试不等于真实供应商联调或所有部署环境验证。

[性能与复现](docs/PERFORMANCE.md) · [验证范围](docs/VALIDATION.md) · [账户安全](docs/SECURITY.md) · [上传内容检查](docs/CONTENT_SECURITY.md)

## 许可与设计说明

- **项目代码：**按 [MIT License](LICENSE) 使用、修改和分发，保留许可证及版权声明。
- **界面设计：**参考研究笔记网站的信息组织方式，页面组件、样式和交互由本项目实现。设计参考见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- **当前分发内容：**当前源码快照和前端构建不包含此前的第三方游戏图片、角色素材与宣传视频。旧素材目录已退出版本跟踪并排除打包。
- **历史版本：**旧 Git 提交仍可能含有第三方素材；MIT 许可不授予这些素材的使用权。获取当前版本请使用本页 ZIP 或浅克隆命令。
- **依赖与用户内容：**第三方依赖、服务组件和自行上传的内容遵循各自许可或权利归属，不能以本项目 MIT 许可替代。
