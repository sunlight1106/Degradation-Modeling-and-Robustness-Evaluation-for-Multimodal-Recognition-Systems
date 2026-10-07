# 启动、更新与耗时排查

## 平时使用

Windows 完成首次安装后双击 `start.cmd`；或运行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1`。Linux / macOS 运行 `sh deploy.sh`。

完整安装会使用 `docker compose start --wait` 启动现有容器，并检查全部服务是否健康。已经运行的服务不会重新启动；日常启动不再进入构建、拉取镜像或重建容器的流程。Docker Desktop 关闭时，Windows 脚本仍需先等待 Docker 引擎启动。

第一次安装、缺少服务容器或明确请求完整更新时，仍然需要构建和下载。**删除容器后重新安装与停止后重新启动，耗时不同。** 停止项目使用 `docker compose stop`；保留数据卷和容器便于下次启动。

## 修改代码以后

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1 -Build
```

Linux / macOS：`sh deploy.sh --build`。构建期间原应用仍可使用；构建完成后停止旧应用进程，让新版后端和任务服务并行初始化。两者都通过 Flyway 数据库锁等待迁移完成，之后才校验数据库结构。健康检查通过后重新启动前端，更新它的后端地址。原有 MySQL、Redis、MinIO 和 ClamAV 容器保留。

如果还修改了基础服务的 Compose 配置，使用 `-FullBuild` 或 `--full-build` 应用整个部署。此模式可能编译 MinIO 等基础组件，不应作为日常启动命令。不要用 `docker compose down -v` 加速启动，这会删除数据卷。

`-SkipBuild` / `--skip-build` 是严格复用模式，缺少服务时会报错，而不会偷偷开始编译。

## 本轮优化

- 日常启动与更新构建分开，避免重复触发基础镜像构建；应用更新也不强制重建存储服务。
- MySQL、Redis、ClamAV、后端、任务、训练及前端在启动阶段更频繁地检查就绪状态，启动后恢复原来的检查周期。
- ClamAV 加载病毒库与后端初始化可以重叠。后端就绪检查仍需数据库、Redis、对象存储与 ClamAV 响应正常，上传仍执行强制扫描。
- 任务服务不再等待整个后端启动完毕才开始初始化。它启用与后端相同的 Flyway 迁移锁，两个进程可以安全并行启动；数据库迁移失败仍会阻止启动。
- 预置知识库复查只查询主题及标题，不再逐个主题加载所有卡片正文和实体；保留用户编辑内容和首次初始化行为。
- 后端镜像在构建时展开依赖，运行时走普通 JVM 类路径，减少嵌套 JAR 的加载开销；仍保留正常即时编译和全部启动校验。
- 两个 Java 进程默认各使用 2 个即时编译线程，避免多进程启动时抢占 CPU。这不是限制业务线程或关闭 C2 优化。可在 `.env` 设置 `JAVA_COMPILER_THREADS`（至少 2）后更新应用容器；更大值是否有利需要按部署机器及负载复测。
- Docker 引擎探测每次最多等待 5 秒，避免一次卡住的探测让启动脚本长时间无反馈。此改动不承诺缩短 Docker Desktop / WSL 本身的冷启动时间。
- 构建上下文排除本地运行环境、恢复归档、上传数据及工具缓存。
- 后端构建只复制所需源码、测试和数据资源；修改数据库说明文档或训练模块不再使 Java 编译缓存失效。
- 首次词库导入启用 JDBC 批量写入，避免驱动把每组 500 条插入拆成逐条往返；已经应用的迁移不变、更不重跑。
- 前端静态脚本与样式启用 Gzip 压缩并继续使用内容哈希缓存，减少网络下载；接口与实时消息流不受此压缩配置影响。

没有通过关闭鉴权、禁用扫描、跳过数据库迁移或把首次请求的初始化成本藏起来来换取启动数字。数据库组件的延后初始化曾单独试测，没有稳定收益，因此未启用。

## 2026-10-07 本地实测

同一台 Windows 电脑、同一份已有数据、8 个服务，Docker 可用 18 个逻辑处理器和约 15.4 GiB 内存。Docker 引擎保持运行，计时从已停止的服务开始启动算起；不包含停止过程、Docker Desktop 冷启动、构建、下载或首次导入词库。测试时没有同时构建或训练。

| 指标 | 修改前 | 修改后，第 1 次 | 修改后，第 2 次 |
| --- | ---: | ---: | ---: |
| 全部服务健康 | 90.72 秒 | 37.71 秒 | 33.83 秒 |
| 经前端代理的公开接口可用 | 54.00 秒 | 36.60 秒 | 32.74 秒 |

整套服务启动耗时减少约 **58%–63%**。服务已经运行时，再执行 Windows 日常启动脚本实测 **4.6 秒**，其中 Docker 检查 1.1 秒、项目检查 3.4 秒（显示值四舍五入）。它只是确认现有服务健康，不代表从停止状态启动只需 4.6 秒。

重启后的实际请求测量，每个只读接口先记录首次请求，再顺序采样 15 次：

| 请求 | 首次 | 后续中位数 | 后续 P95 |
| --- | ---: | ---: | ---: |
| 首页 HTML | 25 ms | 3 ms | 28 ms |
| 工作台 | 132 ms | 50 ms | 70 ms |
| 笔记列表 | 43 ms | 38 ms | 67 ms |
| 知识库主题 | 67 ms | 54 ms | 77 ms |
| 全局搜索 | 66 ms | 35 ms | 65 ms |

首次登录约 **1.07 秒**，与修改前的 1.08 秒基本相同。入口脚本和样式的实际传输体积由 **357,951 字节降至 100,268 字节**，减少约 **72%**；已验证解压内容与原文件完全一致。这里是本机回环 HTTP 测量，不是浏览器完成渲染的时间，也不能代替公网延迟或多人并发容量测试。线上服务常驻，用户访问不需要重新启动整套容器。

校验包括：后端构建中 262 项测试通过（另有 29 项可选测试跳过）；另行在隔离 MySQL 8.4 上执行全新数据库、旧版升级两项并发迁移测试，均通过；Windows/Linux 启动决策检查、Nginx 配置检查、8 个运行服务的健康检查均通过。预置知识库重复初始化测试确认只执行 2 次查询、不读取卡片正文、不覆盖已编辑内容。

## 怎样复测

Windows 启动结束会显示 Docker、项目和总耗时，并在 `.runtime/startup-last.json` 保存本次记录。Linux / macOS 也显示两个阶段的耗时。

完整重启测量（会短暂中断项目，不停止 Docker Desktop，也不删除数据）：

```powershell
python scripts/performance/compose_start_benchmark.py --restart --url http://127.0.0.1:4173 --output .runtime/startup-check.json
```

把端口改成当前 `.env` 中的 `WEB_PORT`。报告记录各服务首次健康时间、网页接口可用时间和全部服务就绪时间。对照测试应在相同机器、相同数据、相同 Docker 分配下串行进行，不同时构建或训练；首次下载病毒库不应与已有病毒库的启动混为一谈。

普通请求测量：`python scripts/performance/http_latency.py --output .runtime/http-check.json`。它使用本地 `.env` 的管理员账户登录一次，测量首页和只读接口后撤销这次会话；只保存耗时，不保存密码、令牌或响应内容。若管理员已改密，需使用有效的本地测试凭据；不要为了测试重置真实账户密码。这项测量不包含浏览器渲染、外网距离、并发压测或模型生成时间。

相关机制：[Docker Compose 启动已有容器](https://docs.docker.com/reference/cli/docker/compose/start/)、[依赖顺序与健康检查](https://docs.docker.com/compose/how-tos/startup-order/)、[Flyway 并发迁移锁](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-lock-retry-count-setting)、[Spring Boot 容器打包](https://docs.spring.io/spring-boot/3.3/reference/packaging/efficient.html)、[Java 编译线程选项](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html)、[MySQL 批量写入](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-performance-extensions.html)。
