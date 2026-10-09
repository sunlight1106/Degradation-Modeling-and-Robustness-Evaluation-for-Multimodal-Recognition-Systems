# 词书与背单词使用指南

## 先选一本，马上开始

1. 登录后打开「目录与账户 → 背单词」，直接点开始学习。首次自动采用电脑时区；学习设置里可随时调整每日目标和时区。
2. 打开「我的词书」，按 **考研 / 四六级 / 留学考试 / 中小学 / 专业与通用** 筛选，也可以输入名称搜索。
3. 点「查看单词」先了解收录内容，或点「选这本词书」再「开始学习」。同一账号的同一个单词跨词书共享进度；学习、收藏、错词、复习日和 Skip 都会接续。
4. 默认「每日带练」：看场景、音标与搭配 → 点「遮住答案，开始回忆」→ 输入英文 → 答完再看用法。之后交替练拼写和固定搭配；可以用 sb./sth. 作占位。也可切换「释义识记」四选一，用按键 1–4 选答案，Enter 继续。
5. 熟词点 **Skip**，会在所有词书中跳过，不冒充掌握。到「单词本 → 已跳过」点「恢复学习」，原进度仍在。
6. 「单词本」可以搜索单词、查看释义、收藏或筛选错词；「学习记录」显示近期进度。

## 内置词书

现在共 **18 本**：14 本考试与主题词书、3 本原创入门词书，以及 **每日 · 场景与搭配（86 词）**。
新增词库包含 **14,894 个不同单词，43,632 个词条收录**；连同原有 60 词，全部词书合计 43,778 个收录。不同书之间有重叠，不能把收录总数当成不同单词数。

| 词书 | 实际收录 |
| --- | ---: |
| 考研英语 · 综合词汇 | 4,796 |
| 大学英语四级 | 3,844 |
| 大学英语六级 | 5,401 |
| 雅思 IELTS 词汇 | 5,026 |
| 托福 TOEFL 词汇 | 6,941 |
| GRE 进阶词汇 | 7,484 |
| 初中英语 · 中考词汇 | 1,599 |
| 高中英语 · 高考词汇 | 3,666 |
| 考研英语 · 高频 1500 | 1,500 |
| 小学衔接 · 常用 500 | 500 |
| 高中英语 · 基础 1000 | 1,000 |
| 学术阅读 · 进阶 1500 | 1,500 |
| 计算机英语 · 核心术语 | 190 |
| 商务英语 · 职场沟通 | 185 |

- 考试分类来自 ECDICT 的已有标签，使用通用语料词频排序；不代表当前考试官方完整大纲。“高频”不代表历年试卷出现次数。
- 小学衔接、基础、学术及专业词书是按说明筛选的学习合集。人教版具体年级、册次和单元尚未内置。
- 没有把通用词库标为“闪过 2028”“红宝书 2028”等出版物。需要某一具体版本时，可以私有导入自己有权使用的内容。
- 原有入门书包含原创例句。新增词条保留 ECDICT 的中文释义与音标；原来缺少的 440 条音标已补充，其中有美式词典转换和补充标注。完整音标不代表统一口音。8,665 个不同词已有搭配；未收录搭配的词会明确显示，可在私有词书中补充。例句缺失时不伪造示例。练习干扰项为自动筛选，并排除中文释义字符重叠，仍可结合上下文辨析多义词。

## 导入自己的版本

点击「导入自己的词书 → 填入原创示例」，按示例替换内容，或读取 UTF-8 JSON 文件，再勾选内容使用权确认并导入。每本 **4–500 词**，每个账号最多 **20 本**，文件最大 **2 MB**。较大的教材请按册次或单元分成多本；这项导入容量限制不影响内置大词书。

每个词需要英文、音标、词性、一个明确中文释义、例句及译文，还有 3–8 个不同且不与正确义项重叠的干扰项。模板见 [import-example.json](vocabulary/import-example.json)。填写自己的书名和来源说明，导入后仅当前账号可见，管理员也不会因此获得读取权限。

词书数据随 Docker 镜像安装，启动与学习时无需再联网下载，也不会触发模型 API 费用。新增公共词书不会覆盖原来的私人词书和学习记录。

## 备份与恢复（不用手动改 JSON）

1. 打开背单词页面右上角 **备份与恢复**，点 **下载学习备份**，获得 `.json.gz` 文件。每次答题自动保存到服务器；下载文件用于另存副本，不会自动上传其他云盘。
2. 恢复时登录目标账号，选择该文件，勾选合并确认，再点 **合并恢复**。支持压缩或普通 JSON，文件最大 20 MB、解压最大 64 MB。
3. 私有词书使用新账号的独立 ID；相同内容的词书合并，同词进度不累加重复次数，重复恢复同一文件不会双倍计数。现有学习次数与错词次数保留较大值；复习日期、收藏和 Skip 采用较新的记录。
4. 备份只含当前账号的词书、学习记录和设置，不含密码、API 密钥、会话或其他账号数据。它不能代替整个服务器的数据库与媒体备份。跨设备接续需要登录同一套服务器上的同一账号；单独下载代码不包含私人进度。

## 每日带练如何算进度

看过不算掌握。即时答对、提示后答对、隔开一段时间的无提示答对分开记录。带练中查看答案后至少间隔 60 秒，未使用提示答对才增加四次掌握计数；这是产品规则，不是长期记住的保证。连续练其他单词后再回头，次日再混合检查。不能把同屏看着英文输入当作独立回忆。

## Learning rules

“初步掌握” means a product threshold, not a scientific guarantee of permanent memory.

1. In recognition mode a correct LEARN choice increments the shared headword count once. In guided recall only a delayed, unprompted correct answer earns that count; immediate and prompted success remain separate evidence. An incorrect answer adds a mistake and does not increment or erase previous correct answers.
2. At **4** correct answers the entry enters review, due on the **next local calendar date** in the user's explicitly saved IANA learning timezone.
3. A correct due review schedules the next review in **3, 7, 14, 30, then 60 days**; later correct reviews keep a 60-day interval. A wrong review resets the interval stage and schedules the next local day.
4. MISTAKES practice clears the mistake flag on a credited correct answer but never increments learning or advances review. Historical wrong counts remain available.
5. The daily goal counts distinct newly learned words across all books. An unfinished small batch rotates between entries; already started entries can be finished after lowering the goal. Switching books retains all progress.
6. Day records use the saved timezone at answer time. A later timezone change does not relabel historical study dates. DST is handled as calendar dates, rather than adding fixed 24-hour durations.

## Integrity and privacy

Every mutation locks the authenticated user's `app_user` row inside an explicit READ_COMMITTED database transaction. READ_COMMITTED matters on MySQL because resolving the principal before acquiring the lock must not leave subsequent question/progress reads on a stale REPEATABLE_READ snapshot. Question/progress mutations additionally use pessimistic row locks. This serializes updates across application instances, avoids first-progress-row races, and ensures each submitted question grants credit at most once. The same completed question returns its original answer result on retry.

Only one unexpired question is active per account. Repeated next-question calls for the same book/mode/style return the same question. Changing book/mode or the learning timezone expires the previous one. Questions expire after 30 minutes. Hints are tracked on the server; Skip grants no answer credit. Stale, foreign, or invalid-option submissions fail without modifying progress.

Question payloads expose the prompt, IPA, part of speech and randomized opaque option IDs, but no correct-answer field, word database ID or example before submission. Grading uses the persisted server answer. This is a learning tool, not an anti-cheating exam: authorized word browsing intentionally exposes definitions.

The frontend uses Vue text interpolation for imported text, not raw HTML. Progress stays in server storage; there are no cross-account browser progress caches. Account export includes only the user's private book content, settings and progress, and deliberately excludes question answer snapshots.

## 数据来源与更新

新增词条来自 [ECDICT](https://github.com/skywind3000/ECDICT)，固定提交 `bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b`。保留 [MIT 许可全文](../database/vocabulary/ECDICT-LICENSE.txt)。[catalog.json](../database/vocabulary/catalog.json) 记录源文件与压缩词库的 SHA-256、词量和版本。

从该提交下载 `ecdict.csv` 后，可用 `python scripts/build-vocabulary-catalog.py /path/to/ecdict.csv` 重建；脚本先核对源文件哈希，再按标签/词频/人工主题表选词。释义选择保留长度合适的完整一行，过滤无中文释义、无效拼写及大小写重复。音标沿用源文件，未统一口音。压缩数据只保存一次各个词条，各词书引用同一份数据；数据库保存各词书内容，学习状态以账号和规范化单词为唯一键共享。不混合同根词、不同词形或不同账号。

V22–V23 保留旧答题历史并合并同账号的重复单词进度；V24 添加场景带练课。V17 数据库迁移自动导入该离线包，原有 V11 入门词库不变。**已经发布的 V17 数据包和迁移不可原地改写**；后续词库修订须新增迁移，以保留升级校验与已有学习记录。迁移校验包含压缩包内容，缺失或损坏时启动失败而非静默提供空词库。

## Exporting and reimporting private books

Settings → Privacy and data → Export my data requires the current account password. The account JSON has `schemaVersion: 3`; it is a personal data copy, **not a complete system backup or an account-restore format**. It excludes uploaded file bytes, passwords, API credentials, sessions, question/answer snapshots and other accounts' data. It cannot recover media or keys. System recovery requires separately verified database, object-storage and encryption-key backups.

The existing `vocabularyBooks`, `vocabularyWords`, `vocabularyProfile` and `vocabularyProgress` sections remain available for inspection. `vocabularyWords.distractors` now contains the stored JSON-encoded list of incorrect meanings. Older account exports (version 1) omitted this list and cannot reconstruct a private book completely without the original import or manually supplied distractors.

For content round trips, use **`vocabularyBookImports`**. Each object is one self-contained private-book import with its own `schemaVersion: 1`, title, description, original attribution and ordered words. Every word preserves the stored term, IPA, part of speech, focused meaning, example, translation and ordered distractors. The schema has one focused meaning per word, not a separate multi-sense dictionary model. The portable section excludes shared starter books, owner/account identifiers, database IDs and study progress.

1. Download the account JSON from Settings. Keep it private; it contains personal data.
2. Copy just the desired object from `vocabularyBookImports` into a separate UTF-8 `.json` file. Do not import the whole account export or the raw SQL-shaped vocabulary rows. This local extraction example creates a new file and will not overwrite an existing one (change the input filename and `index` as needed):

   ```python
   import json
   from pathlib import Path
   account = json.loads(Path("pkb-ai-evaluation-data.json").read_text(encoding="utf-8"))
   if account.get("schemaVersion") not in (2, 3):
       raise ValueError("Expected account export version 2 or 3")
   index = 0
   book = dict(account["vocabularyBookImports"][index])
   if book.pop("schemaVersion", None) != 1:
       raise ValueError("Expected portable book version 1")
   book.pop("rightsConfirmed", None)  # No carried-over consent; the import UI requires confirmation.
   with Path("private-wordbook.json").open("x", encoding="utf-8") as output:
       json.dump(book, output, ensure_ascii=False, separators=(",", ":"))
   ```

3. In Vocabulary → Import private book, read that file, review the content and explicitly confirm you have the right to use it. Exports deliberately set `rightsConfirmed: false`; exporting is not renewed permission to use or share someone else's content. API callers must likewise set it to true only after confirmation.
4. Import creates a **new** private book owned by the currently authenticated account, with new book/word IDs and no restored progress, stars, questions or review dates. It never updates an existing book or transfers ownership of the original. The original attribution is retained verbatim after the import's normal surrounding-whitespace normalization. Attribution is user-provided provenance, not a verified license or authenticity certificate.

Legacy unversioned book imports remain supported. Explicit versions other than 1 are rejected. All existing validation still applies: 4–500 words/book, 20 private books/account, a 2 MiB request limit and bounded fields/distractors. A historical unversioned import within a few bytes of the 2 MiB cap may produce a versioned portable object over that cap because of metadata; it is not guaranteed to reimport unchanged. The extraction example checks the portable version, then emits the supported unversioned format without a consent flag; this avoids adding format metadata or whitespace to a historical import already near 2 MiB. The import UI still requires its explicit rights checkbox, and the API requires `rightsConfirmed: true`. Importing a copy uses another book slot; it does not deduplicate or reset the original.

## API

All endpoints require authentication and use the current account; client-supplied owner IDs are ignored.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/vocabulary/dashboard` | Books, personal settings, daily stats, streak, history |
| `PUT /api/v1/vocabulary/settings` | Save IANA timezone, daily goal and selected book |
| `POST /api/v1/vocabulary/next` | Create/resume LEARN, REVIEW or MISTAKES question |
| `POST /api/v1/vocabulary/questions/{id}/answer` | Validate option and apply a single answer transaction |
| `GET /api/v1/vocabulary/books/{id}/words` | Search/filter 30 words/page |
| `PUT /api/v1/vocabulary/words/{id}/star` | Set personal star state |
| `POST /api/v1/vocabulary/books/import` | Validate and atomically create a private wordbook |

## Automated verification

`VocabularyIntegrationTest` exercises the actual Spring MVC/security/service/persistence paths on H2:

- Authentication, timezone validation and goal validation
- Account export → fresh-account import → export equality for complete canonical private-book content and attribution, explicit rights reconfirmation, new IDs/ownership, no copied progress, unauthorized/other-owner denial, schema rejection, 500-word/20-book bounds, and compact extraction/reconfirmation for an unversioned import within 10 bytes of the 2 MiB cap
- Complete 60-word original seed and 4 distinct options with no answer leak
- Invalid options do not mutate state; refresh resumes the same question
- 4-correct transition, daily goal, no premature review, cross-account progress isolation
- Wrong answers, mistake remediation, unchanged learning/review during practice
- Six concurrent next-question requests, six duplicate submissions, and replay of a changed answer
- Round-robin batch, replaced questions and 30-minute expiration
- Local midnight and spring/fall DST boundaries
- Review interval growth and lapse reset
- Private import/book/word/star/question isolation and atomic validation failures
- Search, filters, page limits and per-user progress

Run `./mvnw -Dtest=VocabularyIntegrationTest test` and `npm run build --prefix cle`.

`VocabularyMySqlIntegrationTest` inherits the same full contract and runs against a new, random `rv_vocab_test_*` schema when `MYSQL_TEST_URL` is set, using `MYSQL_TEST_USERNAME` and `MYSQL_TEST_PASSWORD`. It runs all Flyway migrations, validates Hibernate mappings and drops only the schema it created. It never migrates, resets or drops an existing selected database. Run `./mvnw -Dtest=VocabularyMySqlIntegrationTest test` inside the verified MySQL network namespace.

## 自己检查是否正常

1. 登录，打开「背单词」，直接开始，系统自动采用电脑时区，默认每天 10 词。选「每日场景课 · 86 词」，点「学习新词」。
2. 第一张卡应显示音标、场景、完整搭配、例句；点「遮住答案」后英文和音标应隐藏，输入框可直接打字。
3. 输入答案，确认反馈区区分即时、提示后和延迟无提示回忆。先练其他词，至少隔开 60 秒后再无提示回忆，才增加带练掌握次数。
4. 点 Skip，再在单词本筛选「已跳过」。点「恢复学习」，次数与复习进度应保留。换到收录同一个词的另一本书，也应接续该词的进度。
5. 下载学习备份，重新选择该文件并确认恢复。重复恢复后词书数量、答题次数不应加倍。
6. 退出并换另一个账号。其个人进度、私有词书和备份必须与前一个账号隔离。
7. 想快速检查旧练习，可切换「释义识记」四选一：累计四次正确选择后进入次日复习。新带练不能用连续看答案输入来完成这项检查。
8. 次日做「到期复习」，检查新的复习日。不要直接修改正在使用的数据库来模拟日期变化。

桌面检查包括答案遮挡、输入、提示、反馈、Skip、备份入口和键盘焦点；界面测试使用合成账号数据。接口与权限测试另在 H2 和独立 MySQL 8.4 数据库执行，不混入真实学习记录。

## Deliberate first-version limits

No copied full commercial catalog, real-person recordings, speech scoring, offline/PWA sync, reminder notifications, or native-app parity is claimed. There is no automatic external dictionary call and no API key is needed for this module. The spaced schedule above is explicit and testable, rather than a claim to reproduce another product's private algorithm.
