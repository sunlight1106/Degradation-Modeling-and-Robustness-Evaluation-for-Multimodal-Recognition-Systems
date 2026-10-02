# Personal AI (BYOK)

Each authenticated user manages their own encrypted provider settings. No administrator or pooled key is used by personal notebook AI. Administrator privileges do not grant access to another user's personal profile, history, or preview. Provider billing belongs to the user's provider account; this feature does not debit the platform wallet or invent cost estimates.

## Safe deployment defaults

- `PERSONAL_AI_REMOTE_ENABLED=false` is the default. Settings and previews work, but no remote execution occurs. Enable only when the deployment is ready for user-authorized provider calls.
- `CREDENTIAL_MASTER_KEY` must be an independently generated secret of at least 32 UTF-8 bytes. Missing, short, and the former known development key fail startup. Preserve it securely across restarts; changing it without re-encrypting records invalidates existing credentials. AES-256-GCM uses a fresh random nonce per encryption.
- Never place provider keys in source control, frontend environment files, browser local storage, logs, URLs, screenshots, or shared administrator configuration. Enter keys through the signed-in user's settings page over HTTPS. Key fields are write-only, blank updates retain the existing key, and responses reveal only `configured` metadata.
- `PERSONAL_AI_ALLOWED_BASE_URLS` is an optional comma-separated list of exact HTTPS API bases. Empty by default. It permits a deployment-reviewed custom gateway or regional endpoint; wildcards and arbitrary domains are not supported. Allowlisting does not permit private/reserved addresses. Treat the configured endpoint operator as a recipient of both the user's API key and approved content.

## API workflow

All paths are under `/api/v1/personal-ai` and require authentication. Preview and execution also require `note:write`.

- `GET /providers`: provider, displayName, protocol, known baseUrls, customEndpointAllowed, remoteEnabled. Model IDs are user supplied; no model is silently selected.
- `GET /settings`, `PUT /settings/{provider}`, `DELETE /settings/{provider}`: current user's profiles only. PUT accepts `{model, baseUrl, apiKey, enabled}`. A new profile requires a key. No owner ID is accepted.
- `GET /usage`: current user's most recent 100 calls, with status, provider/model/action, token counts when returned, sanitized error code, and timestamp. No prompts, generated content, keys, or estimated costs are stored.
- `POST /preview`: `{provider, action, title, body, selectedTaskIds}`. Actions: summarize, outline, tags, tidy, draft. At most 20 own completed experiment IDs are resolved on the server, including input-file ownership. Total body plus resolved sources is limited to 24,000 characters, with no silent truncation. The response displays the exact user context, system instructions, endpoint/model and outbound byte count.
- `POST /execute`: `{previewToken, confirmed:true}`. A five-minute, single-use token binds the owner, exact serialized request, and setting identity/revision. Other users cannot consume it. Configuration changes or deletion invalidate it. Edits require a new preview. Selected resource ownership is rechecked at execution; rechecked content never replaces or expands the approved payload. Restarting the process invalidates pending previews.
- Legacy `/notes/assist` and `/notes/{id}/assist` perform explicit `LOCAL_RULES` only and reject bodies over 24,000 characters without truncating or modifying the original. They never silently fall back from a provider call or access the pooled key ring.

Failed, refused, incomplete, oversized, or empty provider responses fail closed. The user can choose local rules separately. Generated text is untrusted and should be reviewed before applying; it must be rendered with Markdown sanitization and without automatic external image fetches.

## Protocols and endpoint policy

- OpenAI, xAI, DeepSeek, Moonshot, Qwen, and hosted Llama/custom compatible endpoints use chat completions with Bearer authentication.
- Anthropic uses `/messages`, `x-api-key`, `anthropic-version: 2023-06-01`, top-level `system`, and text content blocks.
- Gemini uses `/models/{model}:generateContent`, `x-goog-api-key`, `systemInstruction`, `contents`, and `generationConfig`; thought parts are excluded from output.
- Hosted Llama means a provider such as Groq or Together, not an assumed universal Meta endpoint. Model availability and vision support depend on the user's account and selected model.
- Qwen region-bound keys and workspace endpoints must match. Known legacy DashScope bases remain selectable for existing accounts, but current workspace-specific regional addresses require explicit deployment allowlisting. No arbitrary `*.aliyuncs.com` suffix is trusted.

DNS resolution is capped at five seconds in a bounded executor. Every answer must be public; mixed public/private results are rejected. The connection uses the immutable validated DNS snapshot, normal TLS/hostname verification, no proxy, no redirects, and no automatic retries. A new connection pool per request prevents an older pool from bypassing the selected endpoint snapshot. Reserved/private IPv4, loopback, multicast, link-local, IPv4-mapped IPv6, ULA, special-purpose IPv6 and transition ranges are rejected. DNS pinning protects against rebinding between validation and connection.

Calls have an absolute 30-second timeout, eight-second connect and 20-second read timeout, 256 KiB response limit, 24,000-character output limit, and provider token cap of 1,600. Per process: one active call per user, 12 executions and 30 previews per user/minute, 16 calls globally, and at most 256 pending previews. Multi-replica deployments must additionally enforce account/global quotas at a shared gateway or distributed limiter; in-memory limits are not a cluster-wide billing cap. Preview content stays only in bounded process memory until consumed/expired/restarted.

## Verification and limitations

Development verification uses synthetic credentials and mocked upstream responses only. It covers all provider protocol families, owner isolation including administrator accounts, encrypted persistence, exact preview binding, rotation/deletion/expiry/replay, disabled/missing configuration, explicit local behavior, rate/concurrency limits, hostile endpoint inputs, private/mapped IPv6 and mixed DNS answers, DNS pinning, redirect/retry policy, bounded response/timeouts, malformed upstream output, token limits and sanitized errors. This is not a live certification of any account, model availability, regional routing, or provider billing behavior.

Primary protocol references:
- [OpenAI chat completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
- [Anthropic Messages API](https://platform.claude.com/docs/en/api/messages)
- [Gemini generateContent](https://ai.google.dev/api/generate-content)
- [xAI chat completions](https://docs.x.ai/developers/model-capabilities/text/generate-text)
- [DeepSeek chat completions](https://api-docs.deepseek.com/api/create-chat-completion)
- [Moonshot chat](https://platform.kimi.ai/docs/api/chat)
- [Qwen compatibility and regions](https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope)
- [Groq API](https://console.groq.com/docs/api-reference)
- [Together compatibility](https://docs.together.ai/docs/inference/openai-compatibility)

## Image recognition and stored results

`POST /recognition/preview` accepts `{provider,fileId,taskType}` where taskType is RECEIPT or LICENSE_PLATE. It requires a CLEAN scanned, own PNG/JPEG/WebP image up to 5 MiB, plus an enabled own profile. The response identifies the exact image by ID, hash, MIME and size, and shows provider/model and both prompts. `POST /recognition/execute` accepts the one-use preview token and explicit confirmation; `GET /recognition/results` lists the owner's latest 100 saved results. The results can be selected in notebook sources using `r:<id>`. There is no real-video path and no claim that text-only models accept images; unverified DeepSeek vision is rejected.

Image preview building reserves one per-owner and at most two global build slots before loading/encoding media; pending and building image snapshots together are bounded to 16. Preview expiry cleanup runs every 30 seconds. A user cancel stops the browser from applying a late response; a request already sent to a provider may complete and be billed within the server's bounded timeout.

Usage is nullable when the provider does not report valid counters or a network outcome is unknown. Reported usage is retained even for truncated/refused/unusable responses; Gemini thought tokens and Anthropic cache input tokens are included when reported. A failed call does not imply zero cost. Provider pricing categories may differ, so these counts are not a monetary bill. `/api/v1/account/usage` reports owner-only all-time counts and known tokens; `/usage` is the latest-100 history.

## Completion persistence and partial outcomes

Provider I/O runs before short database transactions. Recognition output and its successful-usage row commit together; a rejected write rolls back both. Text completion records only usage metadata and never silently saves or changes the notebook. No database failure triggers another provider request or a second failed-usage write.

Completion responses include `persistenceStatus` (`SAVED` or `UNCONFIRMED`) and nullable `warning`. `SAVED` means the usage transaction committed; for recognition it also means the result committed. An `UNCONFIRMED` response still returns the completed content and reported token counters, but a recognition result has no exposed ID and must not enter the saved-result history or offer a notebook source link. The UI displays a prominent warning and lets the user retain the text manually. A lost commit acknowledgement can mean the rows did save, so refresh history rather than assuming either outcome. Clients must inspect this status even when the HTTP response contains a completed result.

If provider output was unusable or the network outcome was unknown and saving its failure metadata also fails, the API returns `PERSONAL_AI_USAGE_UNCONFIRMED`, keeps known token counters in the sanitized error message, and explicitly warns against resending. Unknown counters remain unknown. Provider charges may still apply; the platform's usage totals may be incomplete until reconciled with the provider. The one-use preview stays consumed in every case.

There is no automatic paid-call replay or durable provider-attempt/reconciliation table. A process crash or lost HTTP response can still prevent delivery of generated content. Refresh saved history and check the provider's records before deciding whether to authorize a new request.

`PersonalAiPersistenceIntegrationTest` runs the same six synthetic-only transaction cases on H2 by default and on a fresh, randomly named MySQL schema when `MYSQL_TEST_URL` is supplied: successful atomic writes, result/usage write rollback, commit-time constraint rollback, lost commit acknowledgement, and retained text/token counts. The test never calls a provider or adopts the database named by the supplied URL.

## Deployment and state

Compose forwards `PERSONAL_AI_REMOTE_ENABLED` and `PERSONAL_AI_ALLOWED_BASE_URLS` to API/worker with safe false/empty defaults. Editing an env file alone does not alter a running container; recreate it through the normal operator workflow. This implementation's previews and fine-grained AI concurrency limits are process-local. Use a single API replica or sticky routing for preview/execute, plus shared gateway quotas before scaling. Restarting an API invalidates its unconsumed previews safely. Existing sessionless JWTs require fresh login after the session migration. No live deployment or real API configuration was changed during development.
