# Personal Knowledge Base and AI Recognition Evaluation

个人知识库与 AI 识别评测。用来写笔记、背单词、整理学习资料，也可以接入自己的 AI 分析图片和代码，与其他用户交流。

项目在自己的电脑或服务器上运行，数据保存在部署这套平台的服务器中。Docker 部署不需要另外安装 Java、Node.js 或 Maven。

[下载 ZIP](https://github.com/sunlight1106/Personal-Knowledge-Base-and-AI-Recognition-Evaluation/archive/refs/heads/main.zip) · [首次启动](#首次启动) · [日常使用](#日常使用) · [更新版本](#更新版本) · [常见问题](#常见问题)

## 下载项目

不熟悉 Git：点击上面的 ZIP 链接，完整解压到一个固定目录，例如 `D:\KnowledgeAI`。打开这个目录，确认里面有 `compose.yaml`、`deploy.ps1` 和 `cle` 文件夹。

使用 Git：

```sh
git clone --depth 1 https://github.com/sunlight1106/Personal-Knowledge-Base-and-AI-Recognition-Evaluation.git
cd Personal-Knowledge-Base-and-AI-Recognition-Evaluation
```

## 首次启动

### 1. 准备 Docker

Windows 和 macOS 安装并启动 [Docker Desktop](https://docs.docker.com/desktop/)。Windows 使用 WSL 2 和 Linux 容器。Linux 安装 Docker Engine 和 Compose 插件。Compose 版本至少为 2.20.2。

建议给 Docker 分配 8 GB 内存。首次构建要下载镜像、依赖和病毒库，需要联网，也会比日常启动慢。当前对象存储构建使用 `linux/amd64`，ARM 电脑需要相应仿真支持。

### 2. 启动项目

Windows：在项目文件夹的地址栏输入 `powershell`，回车后运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1
```

Linux / macOS：在项目目录打开终端，运行：

```sh
sh deploy.sh
```

脚本会生成 `.env`、构建镜像并等待服务就绪。看到 `Ready: http://localhost:4173` 后，打开这个地址。端口有调整时，以脚本输出为准。

`.env` 由脚本生成，里面有管理员初始密码、数据库密码和加密密钥。已有安装会继续使用原文件，不要用 `.env.example` 覆盖它，也不要上传到 GitHub。

### 3. 登录

默认管理员用户名为 `admin`。用文本编辑器打开本机 `.env`，找到 `BOOTSTRAP_ADMIN_PASSWORD=`，等号后面就是初始密码。如果改过用户名，查看 `BOOTSTRAP_ADMIN_USERNAME`。

登录后可以在个人设置中修改密码。普通用户在登录页点击「注册」，也可以由管理员创建账号。旧安装升级后仍使用原来的密码。

如果 4173 端口被占用，首次部署可以这样指定端口：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1 -Port 4273
```

已有 `.env` 时，直接修改里面的 `WEB_PORT`，然后重新运行脚本。

## 日常使用

右上角「目录与账户」可以进入各个功能，也可以切换账号或退出。每个账号都有系统分配的唯一身份码，显示名称可以重复，身份码不会重复。

| 功能 | 入口 | 用法 |
| --- | --- | --- |
| 笔记 | 我的笔记 | Markdown / HTML 左侧编写、右侧预览，AI 解释、润色与自测；自动保存、历史版本、回收站和分享；导出 MD、PDF、Word、HTML、TXT |
| 知识库 | 知识库 | 按主题整理知识卡，或转成自己的笔记继续写 |
| 背单词 | 背单词 | 选择词书，按场景、搭配和拼写练习；同一个词在不同词书中沿用进度；熟词可 Skip；可导入和备份 |
| 学习资料 | 学习中心 | 按关键词检索、收藏资料、保存常用搜索；制作复习卡，使用表格、看板和月历，保存评测报告 |
| 图片理解 | 实验台 | 上传图片，选择自己的视觉模型，提问或识别图片中的票据、车牌等内容 |
| 个人 AI | 设置 → 个人 AI | 设置供应商、模型、地址和密钥，管理共享记忆，运行本地训练 |
| 协作 | 联系人、站内信箱、群组协作 | 通过身份码找人，添加联系人、聊天、收发资料；群内权限单独设置 |
| 平台管理 | 平台总览、用户管理、权限管理 | 查看统计折线图，管理账号、角色、单独用户权限和使用期限 |

普通笔记、词汇学习和交流不需要 AI 密钥。个人 AI 调用需要自己的配置，以及部署者开启远程调用开关。发送前会显示内容预览，确认后才提交给供应商。

实验台的 DEMO 用于体验流程，结果是演示数据；真实视频模型调用尚未开放。本地训练目前是 PyTorch CPU 文本分类器，不能直接训练大型语言模型。代码注释分析是 AI 静态推断，不会在服务器执行用户代码。

详细操作：

- [学习中心、搜索和评测](docs/LEARNING_WORKSPACE.md)
- [个人 AI、共享记忆和训练](docs/PERSONAL_AI.md)
- [群组与站内信](docs/GROUPS.md)
- [管理员和用户权限](docs/ADMINISTRATION.md)

网页顶部的「操作文档」也提供逐步说明，可按 Ctrl K 搜索操作。

常查的资料可以在搜索结果旁点「收藏」，以后从「学习中心 → 我的收藏」打开。常用筛选条件点「保存当前搜索」，起一个名字即可保存。资料搜索支持空格分隔多个关键词、相关性和更新时间排序；Ctrl K 打开的搜索框可用 ↑↓ 选择、Enter 打开、Esc 关闭。操作文档里的 Ctrl K 搜索帮助条目。

## 再次启动与停止

Windows 部署完成后，双击 `start.cmd` 即可启动。平时启动复用已有容器和镜像，不会重新编译。

其他系统也可以在项目目录执行：

```sh
docker compose start --wait --wait-timeout 600
```

停止项目并保留数据：

```sh
docker compose stop
```

不要在普通停止、重启和更新时使用 `docker compose down -v`，它会删除数据卷。启动和构建是两件事，耗时记录见 [启动说明](docs/STARTUP.md)。

## 学习和交流

群组支持公告、置顶消息、会话置顶、未读、免打扰和按身份码查找成员。背单词增加英美音切换、慢速听读、AI 搭配讲解、练习生成与造句纠错；笔记增加概念解释、润色和自测。AI 内容先预览再确认，需要自己的模型配置，不会自动写入笔记或记忆进度。

[群组操作](docs/GROUPS.md) · [背单词](docs/VOCABULARY.md) · [个人 AI](docs/PERSONAL_AI.md)

## 更新版本

更新前做好备份。Git 下载的用户，在原项目目录运行：

```sh
git pull --ff-only
```

然后更新应用：

```powershell
# Windows
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1 -Build
```

```sh
# Linux / macOS
sh deploy.sh --build
```

这会更新前后端、任务和训练服务，数据库迁移在后端启动时执行。原数据和 `.env` 保留。只运行 `start.cmd` 不会安装新代码。

ZIP 下载的用户：下载最新 ZIP 到临时目录，停止原项目，把新版源码覆盖到原项目目录，保留原 `.env`，再执行上面的更新命令。

如果修改了数据库、缓存或存储组件的 Compose 配置，使用 `-FullBuild` / `--full-build`；基础组件可能重新构建。多个源码目录默认使用同一组 Compose 服务，不代表多个独立环境。

旧名称仓库只需更新一次远程地址，本地目录不用改名：

```sh
git remote set-url origin https://github.com/sunlight1106/Personal-Knowledge-Base-and-AI-Recognition-Evaluation.git
```

## 备份与部署

笔记和账号保存在 MySQL，上传资料放在 MinIO，运行数据使用 Docker 命名卷。`database` 目录里的文件是结构和词库，不是当前用户的数据库。

- 完整备份：[Docker 数据加密备份与恢复](docs/COMPOSE_BACKUP.md)。保留数据库、文件和原 `.env`，否则加密后的个人 AI 配置可能无法恢复。
- 词汇备份：背单词页面的备份入口可下载进度和私有词书，在同一账号中恢复时自动合并。
- 个人数据：设置 → 隐私与数据，确认密码后下载自己的数据副本。
- 多人在线使用：[服务器、域名、HTTPS 与公网部署步骤](docs/PUBLIC_DEPLOYMENT.md)。各自在电脑上启动的站点不会互通账号；同一服务器、同一域名才使用同一套数据。

默认只监听本机 `127.0.0.1:4173`。公网访问和异地备份需要另外配置服务器与备份目标。

## 常见问题

| 问题 | 检查什么 |
| --- | --- |
| Docker 没有启动 | 打开 Docker Desktop，等待 Linux 引擎就绪，再运行脚本 |
| 网页打不开 | 使用脚本给出的 Ready 地址；运行 `docker compose ps` 检查服务状态 |
| 首次启动很慢 | 首次需要下载和构建；用 `docker compose logs --tail 100 clamav backend` 查看是否仍在下载或启动 |
| 登录报错或 500 | 用 `docker compose logs --tail 100 backend` 查看错误；旧账号使用原密码，权限变动后需要重新登录 |
| 用户看不到某个功能 | 管理员检查用户角色和单独权限；「单独禁止」会覆盖角色允许 |
| AI 无法发送 | 检查个人配置是否启用，以及部署端的远程调用开关是否打开 |
| 更新后还是旧页面 | 确认使用 `-Build` / `--build` 更新，完成后 Ctrl+F5 刷新 |

排查时可以提供错误码或 traceId，不要公开 `.env`、密钥、密码或私人资料。

## 开发与验证

`cle` 是 Vue 客户端，`src` 是 Spring Boot 后端，`ai` 放模型适配和媒体处理，`database/migrations` 放数据库迁移。具体组件见 [部署结构](docs/STACK.md)。

开发需要 Node.js 22.22.2+（22.x）、JDK 17 和 FFmpeg。只启动前端无法完成登录，还需要数据库和其他服务。

```sh
npm ci --prefix cle
npm run test:unit --prefix cle
npm run test:ui --prefix cle
npm run build --prefix cle
```

后端测试：Windows 运行 `.\mvnw.cmd -B verify`，Linux / macOS 运行 `./mvnw -B verify`。H2 仅用于测试；MySQL 检查步骤见 [数据库说明](database/README.md)。

[自动构建记录](https://github.com/sunlight1106/Personal-Knowledge-Base-and-AI-Recognition-Evaluation/actions) · [验证范围](docs/VALIDATION.md) · [账户安全](docs/SECURITY.md) · [上传内容检查](docs/CONTENT_SECURITY.md)

## 许可

项目代码使用 [MIT License](LICENSE)。依赖和自行上传的内容按各自许可使用，依赖许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
