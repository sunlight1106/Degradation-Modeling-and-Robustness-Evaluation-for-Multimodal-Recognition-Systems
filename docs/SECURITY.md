# Security boundaries and verification

The platform has account ownership checks, not a separately modeled multi-tenant database or a guarantee against every attack. Production TLS, proxy topology, backups, host hardening, upstream-provider security, and complete deployment penetration testing remain operator responsibilities.

## Notebook and shared knowledge

- ADMIN permissions are resolved from the complete current catalog, rather than a stored snapshot. Other accounts resolve role defaults plus individual grants/denials. Both request security and business checks use the same result. New module permissions cover vocabulary, contacts, groups, messaging, personal AI and training.
- Account expiry blocks password login and existing JWT sessions. Administrative security changes revoke target sessions; role changes revoke affected role sessions. Only ADMIN may edit individual access, assign roles, disable users, reset credentials or revoke other users' sessions. Delegated user writers may edit display names only.
- Management statistics and audit logs have separate read permissions. Directory search is paginated; detail responses contain metadata and counts, never notebook bodies, private memories, passwords or model keys. Successful administrative changes record their operator, target and permission changes without secret values. Existing notebook ownership boundaries below remain in place.

- Notebook reads, writes, experiment selections, insertion previews and AI source selection are authenticated and owner-scoped on the backend, including when a caller changes request IDs. A platform administrator does not receive an ownership bypass for personal notebook sources.
- References resolve only files/tasks owned by the note owner or legitimately readable knowledge cards. Previously stored invalid cross-user references no longer resolve private metadata.
- Sharing a note grants the displayed note and legitimate embedded reference metadata through its revocable, expiring token. It does not grant access to underlying file content or experiment reports. Text deliberately copied into the note is part of what is shared.
- Public knowledge cards remain readable. Only administrators can modify global cards; private card and topic ownership must both permit reading.
- Workspace roster disclosure requires MEMBERS_READ; content-only viewers see their own membership. Delegated member writers cannot grant permissions they do not hold or modify owner privileges.

## Requests, logging, and payment sandbox

- Anonymous/source rate limits use the socket peer by default. Client-supplied X-Forwarded-For is ignored unless the peer is listed explicitly in app.rate-limit.trusted-proxies as a literal IP address. Trusted chains are evaluated right-to-left; configure only proxies you operate. The supplied Nginx configuration overwrites client-supplied forwarding headers.
- Redis counter increment and expiry are atomic. A capped local counter provides a per-process fallback during a Redis outage, rather than allowing unlimited requests. Distributed deployments still need healthy shared rate storage; per-process fallback is not a distributed quota guarantee.
- Authentication/account routes have a tighter source budget. Personal AI additionally has per-user request/concurrency bounds.
- Application error logs use route templates and error codes, not raw URLs or exception payloads. Nginx access logs omit URLs because share/payment paths contain tokens; error logging is restricted. Do not enable verbose HTTP/body/header logging in production.
- Demo recharge is explicitly blocked outside model demo mode; it does not process real payments. Private AI provider usage is billed by the user's provider and does not debit the demo wallet.

## Tests and limits

Regression coverage includes forged cross-user notebook/source IDs, old invalid references, valid sharing/revocation, public-card readability, restricted global mutations, workspace permission boundaries, spoofed forwarding headers, bounded Redis-outage behavior, sandbox settlement rejection, and secret-bearing error-log redaction. Bounded query-count tests continue to cover large reference lists. SQL uses bound query parameters; rendered Markdown must continue to use the frontend sanitizer. These checks reduce known risks and are not proof of complete attack resistance.

## Account state and browser identity

Every login has a persisted, expiring server session. Logout revokes that session; password resets and administrative status/role changes revoke affected sessions. Security changes require current-password confirmation or ADMIN authority as appropriate. All administrative writes serialize through an ADMIN-role guard before locking users, then refresh the acting administrator and perform a locking last-admin check; concurrent mutual disabling/demotion cannot remove every active administrator.

Browser requests are bound to the account generation loaded in that tab. Another tab's login change clears private UI instead of silently borrowing the new account token. Late responses and old-account 401s cannot overwrite or sign out a newer account. Test coverage includes those races. Browser-local appearance preferences are scoped by account; they are not server-synchronized preferences.

The JSON envelopes for personal AI, account/auth and notes are bounded before JSON deserialization. Notebook bodies have a 200,000-character maximum. Vocabulary imports have their separate 2 MiB envelope and validated per-field/storage limits. Overall disk quotas, distributed admission control and production operational monitoring are still deployment responsibilities.

The Nginx privacy access log is defined at the server level to override the official image's inherited combined log. Adding a second access log at the same HTTP level would retain both logs ([official logging semantics](https://nginx.org/en/docs/http/ngx_http_log_module.html#access_log)). CI starts a temporary official Nginx container and verifies that a failed upstream request containing a synthetic private path/query never appears in logs.

## 群组与站内信访问范围

站内信是基础模块，由 `message:read` 和管理员配置的角色、单独权限控制。私人收件人由服务端限制为平台管理员或符合群内读写权限的同组成员。群内容、回复和附件按当前成员资格及群角色权限校验，群管理员无法获得平台权限或修改其他群管理员。群消息不进入私人已发送列表，消息附件禁止通过普通文件接口绕过成员检查。群发言和成员变更串行锁定同一个群组，避免成员变更与发言提交交错。完整操作及历史消息可见性见 [群组与站内信](GROUPS.md)。

## 搜索与收藏

搜索、原文读取与收藏列表均重新检查来源的当前访问权限。个人 AI 识别结果要求 `personal-ai:use`；群组来源同时要求 `group:use`、`message:read` 和成员的 `CONTENT_READ`。禁用学习中心也会关闭它的 AI 问答接口。

收藏不保存正文或标题副本；仅保存本人选择的资料标识。常用搜索保存本人的筛选条件，不能据此访问他人的资料。并发保存以账号行锁和数据库唯一约束去重，按账号限制数量；清理只处理当前账号的收藏。
