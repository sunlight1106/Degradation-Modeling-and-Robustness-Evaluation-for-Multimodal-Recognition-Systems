package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentInspectionServiceTest {
    private final ContentInspectionService inspector = new ContentInspectionService();
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 1};

    @Test void permitsSupportedPassiveMediaPdfAndText() {
        assertThat(inspector.inspectMedia(PNG, "image.png", "image/png").contentType()).isEqualTo("image/png");
        assertThat(inspector.inspectAttachment(bytes("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n%%EOF"), "readme.pdf", "application/pdf").extension()).isEqualTo("pdf");
        assertThat(inspector.inspectAttachment(bytes("你好,附件\n1,2"), "data.csv", "text/csv").contentType()).isEqualTo("text/plain");
        assertThat(inspector.inspectAttachment(bytes("{\"value\":42}"), "data.json", "application/json").extension()).isEqualTo("json");
    }

    @Test void markdownCodeExamplesRemainAllowed() {
        inspector.inspectAttachment(bytes("# Code example\n```js\n<script>alert('example')</script>\n```\n"), "notes.md", "text/markdown");
        inspector.inspectAttachment(bytes("```bash\n#!/bin/sh\necho example\n```"), "notes.md", "text/plain");
    }

    @ParameterizedTest @ValueSource(strings = {"<script>alert(1)</script>", "<svg xmlns='x'/>", "<!doctype html><html/>",
            "<!-- comment -->\n<html/>", "<?xml version='1.0'?><svg/>", "#!/bin/sh\necho test", "<?php echo 'test';"})
    void rejectsActiveContentDisguisedAsText(String value) {
        assertCode(bytes(value), "private.txt", "text/plain", "FILE_ACTIVE_CONTENT_BLOCKED");
    }

    @Test void rejectsUtf16ScriptAndInvalidTextEncoding() {
        byte[] script = "\ufeff<script/>".getBytes(StandardCharsets.UTF_16LE);
        assertCode(script, "text.txt", "text/plain", "FILE_ACTIVE_CONTENT_BLOCKED");
        assertCode(new byte[]{0, 1, 2}, "text.txt", "text/plain", "FILE_TYPE_MISMATCH");
        assertCode(new byte[]{(byte) 255, (byte) 128}, "text.txt", "text/plain", "FILE_TYPE_MISMATCH");
    }

    @Test void rejectsExecutableMagicActiveExtensionsAndDeclaredMime() {
        assertCode(bytes("MZ harmless synthetic header"), "text.txt", "text/plain", "FILE_ACTIVE_CONTENT_BLOCKED");
        assertCode(bytes("\177ELF harmless synthetic header"), "text.txt", "text/plain", "FILE_ACTIVE_CONTENT_BLOCKED");
        assertCode(PNG, "payload.js.png", "image/png", "FILE_ACTIVE_CONTENT_BLOCKED");
        assertCode(bytes("plain"), "text.txt", "text/html", "FILE_ACTIVE_CONTENT_BLOCKED");
        assertCode(PNG, "image.png", "image/svg+xml", "FILE_ACTIVE_CONTENT_BLOCKED");
    }

    @Test void rejectsTypeMismatchesWithoutLeakingNames() {
        assertCode(PNG, "secret-private.pdf", "application/pdf", "FILE_TYPE_MISMATCH");
        assertCode(PNG, "image.png", "video/mp4", "FILE_TYPE_MISMATCH");
        assertCode(bytes("plain text"), "image.png", "image/png", "ATTACHMENT_TYPE_UNSUPPORTED");
    }

    @ParameterizedTest @ValueSource(strings = {"/JavaScript", "/J#53", "/Java#53cript", "/Launch", "/EmbeddedFile", "/ObjStm", "/Encrypt", "/SubmitForm", "/GoToE"})
    void rejectsPdfActiveOrOpaqueObjects(String name) {
        assertCode(bytes("%PDF-1.4\n<< " + name + " 2 0 R >>\n%%EOF"), "document.pdf", "application/pdf", "FILE_ACTIVE_CONTENT_BLOCKED");
    }

    @Test void preservesCorrectOfficeMimeTypes() throws Exception {
        for (String extension : new String[]{"docx", "xlsx", "pptx"}) {
            var result = inspector.inspectAttachment(zip(office(extension)), "document." + extension, "application/octet-stream");
            assertThat(result.extension()).isEqualTo(extension);
            assertThat(result.contentType()).doesNotContain("octet-stream");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"../escape.xml", "/absolute.xml", "C:/absolute.xml", "word/../../escape.xml", "word\\escape.xml"})
    void rejectsUnsafeArchivePaths(String path) throws Exception {
        Map<String, byte[]> parts = office("docx"); parts.put(path, bytes("<test/>"));
        assertCode(zip(parts), "document.docx", null, "ARCHIVE_CONTENT_INVALID");
    }

    @Test void rejectsSymlinkCentralDirectoryAttributes() throws Exception {
        byte[] data = zip(office("docx"));
        int central = find(data, new byte[]{80, 75, 1, 2});
        data[central + 5] = 3; // Unix creator.
        int mode = 0120777;
        data[central + 40] = (byte) mode; data[central + 41] = (byte) (mode >>> 8);
        assertCode(data, "document.docx", null, "ARCHIVE_CONTENT_INVALID");
    }

    @Test void rejectsBombsEntryLimitsMalformedAndGenericArchives() throws Exception {
        Map<String, byte[]> parts = office("docx"); parts.put("word/bomb.xml", bytes("<x>" + "A".repeat(100_000) + "</x>"));
        assertCode(zip(parts), "document.docx", null, "ARCHIVE_LIMIT_EXCEEDED");
        parts = office("docx");
        for (int i = 0; i < ContentInspectionService.MAX_ZIP_ENTRIES; i++) parts.put("word/part" + i + ".xml", bytes("<x/>"));
        assertCode(zip(parts), "document.docx", null, "ARCHIVE_LIMIT_EXCEEDED");
        assertCode(bytes("PKbroken"), "document.docx", null, "ARCHIVE_CONTENT_INVALID");
        assertCode(zip(office("docx")), "files.zip", "application/zip", "ARCHIVE_TYPE_UNSUPPORTED");
    }

    @Test void rejectsMacroExternalRelationshipAndNestedArchive() throws Exception {
        Map<String, byte[]> parts = office("docx"); parts.put("word/vbaProject.bin", bytes("synthetic"));
        assertCode(zip(parts), "document.docx", null, "FILE_ACTIVE_CONTENT_BLOCKED");
        parts = office("docx"); parts.put("word/_rels/document.xml.rels", bytes("<Relationships><Relationship TargetMode=\"External\" Target=\"https://example.com\"/></Relationships>"));
        assertCode(zip(parts), "document.docx", null, "FILE_ACTIVE_CONTENT_BLOCKED");
        parts = office("docx"); parts.put("word/inner.xml", zip(office("docx")));
        assertCode(zip(parts), "document.docx", null, "FILE_ACTIVE_CONTENT_BLOCKED");
    }

    @Test void rejectsDoctypeAndMismatchedOfficeType() throws Exception {
        Map<String, byte[]> parts = office("docx"); parts.put("word/document.xml", bytes("<!DOCTYPE x [<!ENTITY sample 'benign'>]><x>&sample;</x>"));
        assertCode(zip(parts), "document.docx", null, "FILE_ACTIVE_CONTENT_BLOCKED");
        assertCode(zip(office("docx")), "document.xlsx", null, "ARCHIVE_CONTENT_INVALID");
    }

    @Test void rejectsCentralLocalNameMismatchAndTrailingData() throws Exception {
        byte[] data = zip(office("docx")); int central = find(data, new byte[]{80, 75, 1, 2}); data[central + 46] = 'X';
        assertCode(data, "document.docx", null, "ARCHIVE_CONTENT_INVALID");
        data = zip(office("docx")); byte[] trailing = java.util.Arrays.copyOf(data, data.length + 1);
        assertCode(trailing, "document.docx", null, "ARCHIVE_CONTENT_INVALID");
    }

    @Test void rejectsTamperedCrcEncryptedAndOversizeEntryMetadata() throws Exception {
        byte[] data = zip(office("docx"));
        int central = find(data, new byte[]{80, 75, 1, 2});
        data[central + 16] ^= 1;
        assertCode(data, "document.docx", null, "ARCHIVE_CONTENT_INVALID");
        data = zip(office("docx")); central = find(data, new byte[]{80, 75, 1, 2});
        data[central + 8] |= 1;
        assertCode(data, "document.docx", null, "ARCHIVE_CONTENT_INVALID");
        data = zip(office("docx")); central = find(data, new byte[]{80, 75, 1, 2});
        data[central + 27] = 1; // Declares more than the 8 MiB per-entry expansion limit.
        assertCode(data, "document.docx", null, "ARCHIVE_LIMIT_EXCEEDED");
    }

    @Test void rejectsDeepXmlWithoutResolvingEntitiesOrExtractingAnything() throws Exception {
        Map<String, byte[]> parts = office("docx");
        parts.put("word/document.xml", bytes("<x>".repeat(65) + "</x>".repeat(65)));
        assertCode(zip(parts), "document.docx", null, "ARCHIVE_LIMIT_EXCEEDED");
    }

    private void assertCode(byte[] data, String filename, String mime, String code) {
        assertThatThrownBy(() -> inspector.inspectAttachment(data, filename, mime)).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(code);
            assertThat(ex.getMessage()).doesNotContain(filename);
        });
    }
    static Map<String, byte[]> office(String extension) {
        String main = extension.equals("docx") ? "word/document.xml" : extension.equals("xlsx") ? "xl/workbook.xml" : "ppt/presentation.xml";
        String type = extension.equals("docx") ? "wordprocessingml.document" : extension.equals("xlsx") ? "spreadsheetml.sheet" : "presentationml.presentation";
        Map<String, byte[]> result = new LinkedHashMap<>();
        result.put("[Content_Types].xml", bytes("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/" + main + "\" ContentType=\"application/vnd.openxmlformats-officedocument." + type + ".main+xml\"/></Types>"));
        result.put("_rels/.rels", bytes("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>"));
        result.put(main, bytes("<document><text>Passive document</text></document>"));
        return result;
    }
    static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(result)) {
            for (var entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return result.toByteArray();
    }
    private int find(byte[] data, byte[] marker) {
        outer: for (int i = 0; i <= data.length - marker.length; i++) {
            for (int j = 0; j < marker.length; j++) if (data[i + j] != marker[j]) continue outer;
            return i;
        }
        throw new AssertionError("Missing marker");
    }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
