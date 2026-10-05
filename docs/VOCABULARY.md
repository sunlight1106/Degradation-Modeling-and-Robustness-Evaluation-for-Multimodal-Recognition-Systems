# Vocabulary learning

## Delivered scope

Visit `/app/vocabulary` after signing in. This is an independent learning feature, not an implementation of a third-party app or its proprietary review algorithm.

- Three original starter books: daily life, travel, and study/research; **20 entries each, 60 total**. These are deliberately labelled starter selections, not complete CET, IELTS, TOEFL, postgraduate, or official examination books.
- Each entry includes an English term, broad American IPA, part of speech, a focused Chinese sense, an original example and translation, and reviewed distractors.
- Book selection, server-persisted per-user progress, four-choice learning, next-day spaced review, mistakes practice, stars, searchable/paginated word lists, a daily goal, current streak, and a 14-day history.
- Private JSON book imports: 4–500 words/book, up to 20 books/account. Both the UI and a pre-Jackson server filter limit imports to 2 MiB (including chunked requests); ordinary writes are capped at 16 KiB. Bean validation then bounds all fields/lists and rejects null word elements. Word text cannot contain control characters, and serialized distractor lengths are bounded before any persistence write. All terms and distractor labels are checked for normalized duplicates. Every word requires 3–8 explicit incorrect meanings; authors must ensure they do not overlap semantically with the focused sense.
- All original books are available to every authenticated account. Imported books, settings and progress are only available to their owner, including when another account has administrative permissions.

## Learning rules

“初步掌握” means a product threshold, not a scientific guarantee of permanent memory.

1. A correct LEARN answer increments that entry's cumulative count once. An incorrect answer adds a mistake and does not increment or erase previous correct answers.
2. At **4** correct answers the entry enters review, due on the **next local calendar date** in the user's explicitly saved IANA learning timezone.
3. A correct due review schedules the next review in **3, 7, 14, 30, then 60 days**; later correct reviews keep a 60-day interval. A wrong review resets the interval stage and schedules the next local day.
4. MISTAKES practice clears the mistake flag when correct but never increments learning or advances review. Historical wrong counts remain available.
5. The daily goal counts distinct newly learned words across all books. An unfinished small batch rotates between entries; already started entries can be finished after lowering the goal. Switching books retains all progress.
6. Day records use the saved timezone at answer time. A later timezone change does not relabel historical study dates. DST is handled as calendar dates, rather than adding fixed 24-hour durations.

## Integrity and privacy

Every mutation locks the authenticated user's `app_user` row inside an explicit READ_COMMITTED database transaction. READ_COMMITTED matters on MySQL because resolving the principal before acquiring the lock must not leave subsequent question/progress reads on a stale REPEATABLE_READ snapshot. Question/progress mutations additionally use pessimistic row locks. This serializes updates across application instances, avoids first-progress-row races, and ensures each submitted question grants credit at most once. The same completed question returns its original answer result on retry.

Only one unexpired question is active per account. Repeated next-question calls for the same book/mode return the same question. Changing book/mode or the learning timezone expires the previous one. Questions expire after 30 minutes. Stale, foreign, or invalid-option submissions fail without modifying progress.

Question payloads expose the prompt, IPA, part of speech and randomized opaque option IDs, but no correct-answer field, word database ID or example before submission. Grading uses the persisted server answer. This is a learning tool, not an anti-cheating exam: authorized word browsing intentionally exposes definitions.

The frontend uses Vue text interpolation for imported text, not raw HTML. Progress stays in server storage; there are no cross-account browser progress caches. Account export includes only the user's private book content, settings and progress, and deliberately excludes question answer snapshots.

## Content provenance and rights

`V11__original_starter_vocabulary.sql` contains the complete 60-entry starter corpus authored for this project. Chinese learning glosses, examples, translations and distractors are original and are distributed under the repository's MIT license. English lexical items and IPA are factual learning data; IPA is supplied as broad American learning guidance and may omit dialect variants. No dictionary scrape, commercial wordbook, branded artwork, audio recording, user-private document, or copyrighted example corpus is included.

General product inspiration: [不背单词 official website](https://www.bbdc.cn/), consulted 2026-10-02 for high-level wordbook/review workflow context only. The implementation and visual design are independent.

The import format is in `docs/vocabulary/import-example.json`. `rightsConfirmed` is intentionally false in the sample. The user must explicitly confirm their right to use all imported content; private import is not permission to republish someone else's corpus. Imported text and examples should be reviewed for correctness and ambiguous distractors.

## Exporting and reimporting private books

Settings → Privacy and data → Export my data requires the current account password. The account JSON has `schemaVersion: 2`; it is a personal data copy, **not a complete system backup or an account-restore format**. It excludes uploaded file bytes, passwords, API credentials, sessions, question/answer snapshots and other accounts' data. It cannot recover media or keys. System recovery requires separately verified database, object-storage and encryption-key backups.

The existing `vocabularyBooks`, `vocabularyWords`, `vocabularyProfile` and `vocabularyProgress` sections remain available for inspection. `vocabularyWords.distractors` now contains the stored JSON-encoded list of incorrect meanings. Older account exports (version 1) omitted this list and cannot reconstruct a private book completely without the original import or manually supplied distractors.

For content round trips, use **`vocabularyBookImports`**. Each object is one self-contained private-book import with its own `schemaVersion: 1`, title, description, original attribution and ordered words. Every word preserves the stored term, IPA, part of speech, focused meaning, example, translation and ordered distractors. The schema has one focused meaning per word, not a separate multi-sense dictionary model. The portable section excludes shared starter books, owner/account identifiers, database IDs and study progress.

1. Download the account JSON from Settings. Keep it private; it contains personal data.
2. Copy just the desired object from `vocabularyBookImports` into a separate UTF-8 `.json` file. Do not import the whole account export or the raw SQL-shaped vocabulary rows. This local extraction example creates a new file and will not overwrite an existing one (change the input filename and `index` as needed):

   ```python
   import json
   from pathlib import Path
   account = json.loads(Path("pkb-ai-evaluation-data.json").read_text(encoding="utf-8"))
   if account.get("schemaVersion") != 2:
       raise ValueError("Expected account export version 2")
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

## Morning acceptance checklist

1. Sign in, open “背单词”, confirm the displayed learning timezone and set a one-word goal.
2. Pick a starter book, answer incorrectly once, and confirm zero credit plus a mistake entry.
3. Answer that same entry correctly four times; confirm 4/4, one newly learned word, and a next-local-day review date.
4. Refresh, log out/in, and change accounts. Own progress should persist; another account should have none.
5. Use wrong-word practice, stars, filters and search. Confirm meanings/examples render as plain text.
6. Open “我的词书 → 导入词书”, load the sample, acknowledge rights and import. Confirm it is invisible from another account.
7. Check mobile layout, keyboard 1–4/Enter, double-click suppression, navigation interruption, error recovery and a second tab replacing an unanswered question.
8. On the next local date, complete a due review and inspect the next due date. Do not alter production database dates to simulate this.

Browser visual QA is a separate acceptance stage: compiler success is not screenshot or interaction verification. This environment has not supplied a supported browser preview for the local app.

## Deliberate first-version limits

No copied full commercial catalog, real-person recordings, speech scoring, offline/PWA sync, reminder notifications, or native-app parity is claimed. There is no automatic external dictionary call and no API key is needed for this module. The spaced schedule above is explicit and testable, rather than a claim to reproduce another product's private algorithm.
