# Upload content security

The media-upload and message-attachment paths inspect the supplied bytes, filename and declared MIME type **before** calling ClamAV, writing object storage or saving a database record. Rejections use generic reason codes; neither archive entry names nor scanner diagnostics are returned to the client.

## Supported passive content

- Media: existing JPEG/JPG, PNG, WEBP, MP4 and WEBM signatures, with matching extension/MIME. A missing or `application/octet-stream` MIME is allowed; conflicting specific types are rejected.
- Text: TXT, CSV, JSON and Markdown decoded strictly as UTF-8 (optional BOM) or BOM-marked UTF-16. Binary/control bytes are rejected. Download content remains `text/plain`.
- PDF: recognizable header and EOF; visible or PDF-name-escaped active/embedded content names are rejected. Forms, encrypted PDFs and object streams are conservatively rejected because this gate cannot inspect them safely. This is not a complete PDF validator or content-disarm system.
- DOCX/XLSX/PPTX: bounded OOXML ZIP packages with matching main content type and required package parts. Correct standard MIME types are persisted for all three formats. XML parts and matching passive raster images are supported. Opaque binary parts (including printer settings), embedded documents, macros, ActiveX/OLE parts and external relationships are rejected. This conservative subset may reject otherwise legitimate Office files; remove such features or upload a passive export.
- Ordinary ZIP/RAR/7z archives were not supported previously and remain unsupported. ZIP64, encryption and nested archives are unsupported.

Dangerous script/executable extensions (including disguised double extensions), active declared MIME types, executable/OLE signatures and HTML/SVG/script/shebang text-document signatures are rejected. Leading BOMs, whitespace and HTML comments do not bypass text checks. This is a structural/heuristic policy and **does not detect all malware or polyglots**. Media signature checks are not full codec validation. PDF parsing, file-format exploits, content concealed in compressed media and novel obfuscation still require a maintained antivirus engine and secure downstream readers.

## Bounded archive inspection

Nothing is extracted to disk or executed. The central directory and streamed local entries must agree; CRCs are checked while reading. Limits are fixed in `ContentInspectionService`:

- At most 256 entries
- At most 8 MiB expanded per entry and 32 MiB expanded total
- At most 100:1 expansion per entry
- XML depth at most 64 and at most 500,000 parser events per XML part

Absolute paths, drive-prefixed paths, backslashes, `.`/`..` path components, duplicate/case-colliding entry names, symlinks and other non-regular Unix entry types are rejected. DTDs/entities and external XML resolution are disabled/rejected. Malformed, unsupported or over-limit archives fail closed.

## Antivirus status and operations

Only the exact NUL-terminated clamd response `stream: OK` produces `CLEAN`. Infection produces `FILE_INFECTED`. Responses are bounded to 4 KiB; malformed, incomplete or unavailable responses follow the existing deployment policy:

- Enabled + required: reject with `VIRUS_SCANNER_UNAVAILABLE` (503).
- Enabled + optional: explicitly record `SKIPPED`, never `CLEAN`.
- Disabled: explicitly record `SKIPPED`, preserving existing configuration behavior.

To require antivirus protection, operators must enable the scanner, set it required, and maintain ClamAV/signature updates. Passing the content gate alone is not a clean antivirus verdict. This change does not enable, deploy or reconfigure services and does not retroactively scan existing files. Existing unsupported attachments require a separate authorized rescan/migration policy.

## Notes and verification

The content gate runs on uploaded file bytes, not note bodies. Markdown notes and fenced code samples remain allowed; safe Markdown rendering/sanitization is a separate control and is not replaced by upload inspection.

Tests use generated passive archives, synthetic executable headers, inactive script strings and the harmless EICAR antivirus test string. A local fake clamd validates framing and clean/infected/error policy. No real malware is downloaded or executed, and these tests do not claim a live ClamAV engine detected malware. Tests also verify rejection before object/database persistence, disabled/unavailable status handling, MIME mismatch, archive traversal/symlink/bomb/CRC structure, active PDF names and external XML rejection.
