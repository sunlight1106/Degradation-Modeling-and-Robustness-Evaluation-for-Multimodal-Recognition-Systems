# Security boundaries and verification

The platform has account ownership checks, not a separately modeled multi-tenant database or a guarantee against every attack. Production TLS, proxy topology, backups, host hardening, upstream-provider security, and complete deployment penetration testing remain operator responsibilities.

## Notebook and shared knowledge

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
