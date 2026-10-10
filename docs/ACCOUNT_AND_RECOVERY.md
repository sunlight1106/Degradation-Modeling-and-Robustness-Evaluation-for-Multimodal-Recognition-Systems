# 账号安全与备份

## 验证邮箱和找回密码

1. 登录后打开「设置 → 安全与登录」。
2. 填写当前密码，点击「发送验证邮件」。从邮箱打开链接并确认。
3. 忘记密码时，在登录页点击「忘记密码」，填写这个已验证的邮箱。
4. 打开重设链接，输入两次新密码。更新后，所有设备都要重新登录。

链接有效期为 30 分钟，使用一次后失效。修改邮箱后需要重新验证；旧邮箱收到的链接不能再使用。找回密码不会关闭已经启用的双重验证。

如果页面提示邮件服务未配置，部署者需要在本机 `.env` 填写：

```dotenv
SMTP_HOST=smtp.example.com
SMTP_PORT=587
SMTP_USERNAME=your-account
SMTP_PASSWORD=your-mail-service-password
SMTP_FROM=your-address@example.com
SMTP_AUTH=true
SMTP_STARTTLS=true
PUBLIC_WEB_URL=https://your-domain.example
```

使用邮件服务提供的 SMTP 凭据。`PUBLIC_WEB_URL` 填实际访问地址；本机使用时可以保留本机地址。完成后依次执行 `docker compose up -d --no-deps backend model-worker` 和 `docker compose restart frontend` 更新配置与前端连接。不要上传 `.env`。

## 验证器与恢复码

1. 在「设置 → 安全与登录」填写当前密码，点击「设置验证器」。
2. 在支持 TOTP 的验证器中手动添加页面显示的密钥。验证周期为 30 秒、六位数字。
3. 输入验证器里的验证码，确认启用。
4. 保存页面显示的八个恢复码，然后重新登录。恢复码只显示一次，每个只能使用一次。

之后登录或切换到这个账号，都要填写验证码或恢复码。管理员也使用这套流程，建议公开部署前启用。启用或停用验证器会退出所有现有登录。连续五次验证失败后需要等待五分钟。

## Windows 每日自动备份

完整备份包含数据库、上传文件、训练数据、Redis 和恢复部署所需的配置。归档采用加密存储，生成后会检查解密认证和各组件校验值。备份期间项目服务会短暂停止，完成或失败后会恢复原先运行的容器；建议安排在不用平台的时间。

在项目目录打开 PowerShell：

```powershell
python -m pip install -r scripts/backup/requirements.txt
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/backup/setup-schedule.ps1 -Time 03:30 -Keep 7
```

默认每天 03:30 备份，保留七份验证成功的自动归档。电脑关闭、睡眠或账号未登录时无法运行；错过的任务会在这个 Windows 账号登录后补做。查看 Windows「任务计划程序」中名称以 `PersonalKnowledgeBackup-` 开头的任务。

密钥由系统随机生成，用 Windows 当前账号加密保存在 `.runtime/backup-schedule/key.dpapi`，不写入 Git。**另行导出恢复密钥很重要：丢失电脑或 Windows 账号后，仅有加密归档不够恢复。**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/backup/setup-schedule.ps1 -ExportRecoveryKey E:\SafeStorage\knowledge-backup-key.txt
```

先创建示例中的 `E:\SafeStorage` 目录，或换成你自己已有的安全目录。该命令不会覆盖已有文件。将密钥移到独立的安全位置，与备份分开保管。不要放进 Git 仓库或普通共享目录。

自动归档保存在 `.runtime/backups/scheduled`。程序只清理自己生成且有验证记录的归档，不会删除手动备份或验证失败的文件。失败或超过两天没有成功备份时，管理员会在通知和「学习中心 → 回收站与恢复」看到提醒。任务日志在 `.runtime/backup-schedule/last-run.log`。

停用自动任务：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/backup/setup-schedule.ps1 -Remove
```

归档和密钥仍会保留。Linux 服务器可用 cron 或 systemd 每日调用 `scripts/backup/scheduled_backup.py`，将独立保存的恢复密码放进进程环境变量 `PKB_BACKUP_PASSWORD`；密码文件仅允许运行任务的账号读取，不要写在命令行参数里。

## 恢复与演练

恢复过程和隔离部署步骤见 [完整备份与恢复](COMPOSE_BACKUP.md)。恢复工具只创建新的数据卷，不覆盖当前运行中的数据。建议定期将加密归档复制到另一台设备，并在独立环境演练恢复。

开发验证可以运行 `python scripts/backup/test_compose_roundtrip.py`。它创建自己的测试容器和数据卷，验证四类数据与配置的恢复，然后清理自己的测试资源。这个演练不等于确认你的每份实际归档都可上线，实际归档还应执行文档中的验证与恢复步骤。
