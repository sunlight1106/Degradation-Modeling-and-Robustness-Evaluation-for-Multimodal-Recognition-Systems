package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** A conservative attachment policy, not a replacement for an antivirus engine or a sandbox. */
@Service
public class ContentInspectionService {
    static final int MAX_ZIP_ENTRIES = 256;
    static final long MAX_ENTRY_BYTES = 8L * 1024 * 1024;
    static final long MAX_EXPANDED_BYTES = 32L * 1024 * 1024;
    static final int MAX_COMPRESSION_RATIO = 100;
    private static final Set<String> ACTIVE_EXTENSIONS = Set.of("html", "htm", "xhtml", "svg", "svgz", "js", "mjs", "cjs",
            "jsx", "vbs", "vbe", "wsf", "wsh", "ps1", "psm1", "psd1", "bat", "cmd", "sh", "bash", "zsh", "php",
            "phtml", "py", "pyc", "rb", "pl", "exe", "dll", "com", "scr", "msi", "jar", "class", "wasm", "lnk",
            "url", "hta", "reg", "docm", "xlsm", "pptm", "dotm", "xlam", "xltm", "ppam", "ppsm", "sldm");
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "csv", "json", "md");
    private static final Set<String> OFFICE_EXTENSIONS = Set.of("docx", "xlsx", "pptx");
    private static final Pattern ACTIVE_TEXT = Pattern.compile("(?is)^(?:<\\?xml[^>]*>\\s*)?(?:<!doctype\\s+(?:html|svg)\\b|<(?:html|head|body|script|svg|iframe|object|embed)\\b|<\\?php\\b|#!)");
    private static final Pattern PDF_ACTIVE_NAME = Pattern.compile("/(?:JavaScript|JS|Launch|OpenAction|AA|RichMedia|EmbeddedFile|EmbeddedFiles|XFA|AcroForm|ObjStm|Encrypt|SubmitForm|ImportData|GoToR|GoToE|Rendition|Movie|Sound)\\b", Pattern.CASE_INSENSITIVE);
    private static final Charset ZIP_CHARSET = Charset.forName("IBM437");

    public InspectedType inspectMedia(byte[] bytes, String filename, String declaredType) {
        InspectedType type = inspect(bytes, filename, declaredType);
        if (!type.contentType().startsWith("image/") && !type.contentType().startsWith("video/")) {
            throw rejected("UNSUPPORTED_FILE_TYPE", "仅支持真实的 JPEG、PNG、WEBP、MP4 或 WEBM 文件");
        }
        return type;
    }

    public InspectedType inspectAttachment(byte[] bytes, String filename, String declaredType) {
        return inspect(bytes, filename, declaredType);
    }

    private InspectedType inspect(byte[] bytes, String filename, String declaredType) {
        if (bytes == null || bytes.length == 0) throw rejected("EMPTY_FILE", "文件不能为空");
        String extension = extension(filename);
        rejectActiveName(filename);
        String mime = normalizeMime(declaredType);
        if (mime.contains("javascript") || mime.contains("ecmascript") || Set.of("text/html", "application/xhtml+xml",
                "image/svg+xml", "application/x-sh", "application/x-executable", "application/x-msdownload").contains(mime)
                || mime.contains("macroenabled")) throw active();
        if (isExecutable(bytes)) throw active();

        InspectedType media = mediaType(bytes);
        if (media != null) {
            boolean matchingExtension = extension.equals(media.extension()) || (media.extension().equals("jpg") && extension.equals("jpeg"));
            if (!matchingExtension) throw mismatch();
            requireMime(mime, media.extension().equals("jpg") ? Set.of("image/jpeg", "image/jpg") : Set.of(media.contentType()));
            return media;
        }
        if (starts(bytes, "%PDF-")) {
            if (!extension.equals("pdf")) throw mismatch();
            requireMime(mime, Set.of("application/pdf"));
            // Decode PDF #xx name escapes so /J#53 and /Java#53cript cannot bypass this conservative policy.
            String pdf = new String(bytes, StandardCharsets.ISO_8859_1);
            String decoded = decodePdfNames(pdf);
            if (PDF_ACTIVE_NAME.matcher(decoded).find() || decoded.toLowerCase(Locale.ROOT).contains("javascript:")) throw active();
            if (!pdf.contains("%%EOF")) throw rejected("FILE_CONTENT_INVALID", "文件内容无效或不完整");
            return new InspectedType("application/pdf", "pdf");
        }
        if (starts(bytes, "PK") || OFFICE_EXTENSIONS.contains(extension)) {
            if (!OFFICE_EXTENSIONS.contains(extension)) throw rejected("ARCHIVE_TYPE_UNSUPPORTED", "仅支持经过检查的 DOCX、XLSX 或 PPTX 文档，不支持普通或嵌套压缩包");
            String officeMime = officeMime(extension);
            requireMime(mime, Set.of(officeMime, "application/zip", "application/x-zip-compressed"));
            inspectOffice(bytes, extension);
            return new InspectedType(officeMime, extension);
        }
        if (TEXT_EXTENSIONS.contains(extension)) {
            requireMime(mime, Set.of("text/plain", "text/csv", "application/csv", "application/json", "text/json", "text/markdown", "text/x-markdown"));
            String text = decodeText(bytes).stripLeading();
            // Leading HTML comments do not make an HTML/script document passive text.
            int textStart = 0;
            while (text.startsWith("<!--", textStart)) {
                int commentEnd = text.indexOf("-->", textStart + 4);
                if (commentEnd < 0) break;
                textStart = commentEnd + 3;
                while (textStart < text.length() && Character.isWhitespace(text.charAt(textStart))) textStart++;
            }
            if (ACTIVE_TEXT.matcher(text.substring(textStart)).find()) throw active();
            return new InspectedType("text/plain", extension);
        }
        throw rejected("ATTACHMENT_TYPE_UNSUPPORTED", "附件仅支持图片、视频、PDF、DOCX、XLSX、PPTX、TXT、CSV、JSON 或 Markdown");
    }

    private void inspectOffice(byte[] bytes, String extension) {
        List<ArchiveEntry> directory = readDirectory(bytes);
        String main = switch (extension) { case "docx" -> "word/document.xml"; case "xlsx" -> "xl/workbook.xml"; default -> "ppt/presentation.xml"; };
        String mainType = switch (extension) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
            default -> "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
        };
        Set<String> seen = new HashSet<>();
        boolean validMainType = false;
        long expanded = 0;
        int index = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), ZIP_CHARSET)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (index >= directory.size()) throw invalidArchive();
                ArchiveEntry expected = directory.get(index++);
                if (!expected.name().equals(entry.getName())) throw invalidArchive();
                String name = entry.getName();
                validateEntryName(name);
                if (!seen.add(name.toLowerCase(Locale.ROOT))) throw invalidArchive();
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (content.size() + (long) count > MAX_ENTRY_BYTES || expanded > MAX_EXPANDED_BYTES
                            || content.size() + (long) count > Math.max(1L, expected.compressedSize()) * MAX_COMPRESSION_RATIO) throw archiveLimit();
                    content.write(buffer, 0, count);
                }
                if (content.size() != expected.size() || entry.getSize() != expected.size()
                        || entry.getCompressedSize() != expected.compressedSize() || entry.getCrc() != expected.crc()) throw invalidArchive();
                if (entry.isDirectory()) { if (content.size() != 0) throw invalidArchive(); continue; }
                byte[] part = content.toByteArray();
                if (isExecutable(part) || starts(part, "PK")) throw active();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("vbaproject") || lower.contains("/activex/") || lower.contains("/embeddings/")
                        || lower.contains("/externallinks/")) throw active();
                String suffix = extension(name);
                rejectActiveName(name);
                if (suffix.equals("xml") || suffix.equals("rels")) {
                    validMainType |= inspectOfficeXml(part, name.equals("[Content_Types].xml"), main, mainType);
                } else {
                    // OOXML may embed passive raster images, but opaque binaries and nested documents are intentionally rejected.
                    InspectedType image = mediaType(part);
                    boolean gif = starts(part, "GIF87a") || starts(part, "GIF89a");
                    if (gif) { if (!suffix.equals("gif")) throw mismatch(); }
                    else if (image == null || !image.contentType().startsWith("image/")
                            || !(suffix.equals(image.extension()) || (image.extension().equals("jpg") && suffix.equals("jpeg")))) throw active();
                }
            }
            if (index != directory.size() || !seen.contains("[content_types].xml") || !seen.contains("_rels/.rels")
                    || !seen.contains(main) || !validMainType) throw invalidArchive();
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw invalidArchive(); }
    }

    private boolean inspectOfficeXml(byte[] bytes, boolean contentTypes, String main, String mainType) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicID, systemID, baseURI, namespace) -> { throw new javax.xml.stream.XMLStreamException("External references are disabled"); });
        boolean foundMain = false;
        int depth = 0;
        int events = 0;
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (++events > 500_000) throw archiveLimit();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw active();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if (++depth > 64) throw archiveLimit();
                        if (contentTypes && depth == 1 && (!reader.getLocalName().equals("Types")
                                || !"http://schemas.openxmlformats.org/package/2006/content-types".equals(reader.getNamespaceURI()))) throw invalidArchive();
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            String value = reader.getAttributeValue(i).toLowerCase(Locale.ROOT);
                            String attribute = reader.getAttributeLocalName(i);
                            if ((attribute.equals("TargetMode") && value.equals("external"))
                                    || value.contains("macroenabled") || value.contains("vbaproject")
                                    || value.contains("activex") || value.contains("oleobject")
                                    || value.endsWith("/attachedtemplate") || value.endsWith("/package")
                                    || value.startsWith("javascript:")) throw active();
                        }
                        if (contentTypes && reader.getLocalName().equals("Override")
                                && ("/" + main).equals(reader.getAttributeValue(null, "PartName"))
                                && mainType.equals(reader.getAttributeValue(null, "ContentType"))) foundMain = true;
                    } else if (event == XMLStreamConstants.END_ELEMENT) depth--;
                }
            } finally { reader.close(); }
            return foundMain;
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw invalidArchive(); }
    }

    /** Validate the central directory as well as streaming local entries; never extract to disk. ZIP64/encryption are unsupported. */
    private List<ArchiveEntry> readDirectory(byte[] bytes) {
        if (bytes.length < 22 || u32(bytes, 0) != 0x04034b50L) throw invalidArchive();
        int end = -1;
        for (int i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
            if (u32(bytes, i) == 0x06054b50L && i + 22L + u16(bytes, i + 20) == bytes.length) { end = i; break; }
        }
        if (end < 0 || u16(bytes, end + 4) != 0 || u16(bytes, end + 6) != 0) throw invalidArchive();
        int count = u16(bytes, end + 10);
        if (count == 0 || count > MAX_ZIP_ENTRIES) throw archiveLimit();
        if (u16(bytes, end + 8) != count) throw invalidArchive();
        long offset = u32(bytes, end + 16);
        if (offset + u32(bytes, end + 12) != end || offset > Integer.MAX_VALUE) throw invalidArchive();
        int cursor = (int) offset;
        long totalSize = 0;
        long lastLocal = -1;
        List<ArchiveEntry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < count; i++) {
            if (cursor + 46L > end || u32(bytes, cursor) != 0x02014b50L) throw invalidArchive();
            int flags = u16(bytes, cursor + 8);
            int method = u16(bytes, cursor + 10);
            int nameLength = u16(bytes, cursor + 28);
            int extraLength = u16(bytes, cursor + 30);
            int commentLength = u16(bytes, cursor + 32);
            long next = cursor + 46L + nameLength + extraLength + commentLength;
            if (next > end || nameLength == 0 || (flags & ~0x080e) != 0 || (method != 0 && method != 8)
                    || u16(bytes, cursor + 34) != 0) throw invalidArchive();
            long compressed = u32(bytes, cursor + 20), size = u32(bytes, cursor + 24);
            long local = u32(bytes, cursor + 42);
            int mode = (int) (u32(bytes, cursor + 38) >>> 16) & 0170000;
            if (mode != 0 && mode != 0100000 && mode != 0040000) throw invalidArchive();
            if (local <= lastLocal || (i == 0 && local != 0) || local + 30L > offset || u32(bytes, (int) local) != 0x04034b50L
                    || u16(bytes, (int) local + 6) != flags || u16(bytes, (int) local + 8) != method) throw invalidArchive();
            if (size > MAX_ENTRY_BYTES || compressed > bytes.length || size > Math.max(1L, compressed) * MAX_COMPRESSION_RATIO
                    || (totalSize += size) > MAX_EXPANDED_BYTES) throw archiveLimit();
            Charset charset = (flags & 0x0800) != 0 ? StandardCharsets.UTF_8 : ZIP_CHARSET;
            String name;
            try { name = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, cursor + 46, nameLength)).toString(); }
            catch (CharacterCodingException exception) { throw invalidArchive(); }
            validateEntryName(name);
            if (!names.add(name.toLowerCase(Locale.ROOT))) throw invalidArchive();
            int localNameLength = u16(bytes, (int) local + 26), localExtraLength = u16(bytes, (int) local + 28);
            long dataStart = local + 30L + localNameLength + localExtraLength;
            if (localNameLength != nameLength || dataStart + compressed > offset) throw invalidArchive();
            for (int n = 0; n < nameLength; n++) if (bytes[(int) local + 30 + n] != bytes[cursor + 46 + n]) throw invalidArchive();
            entries.add(new ArchiveEntry(name, compressed, size, u32(bytes, cursor + 16)));
            lastLocal = local;
            cursor = (int) next;
        }
        if (cursor != end) throw invalidArchive();
        return entries;
    }

    private void validateEntryName(String name) {
        if (name.length() > 512 || name.startsWith("/") || name.contains("\\") || name.contains(":")
                || name.chars().anyMatch(c -> c < 32 || c == 127)) throw invalidArchive();
        for (String component : name.split("/", -1)) if (component.equals("..") || component.equals(".")) throw invalidArchive();
        if (name.contains("//")) throw invalidArchive();
    }

    private void rejectActiveName(String filename) {
        if (filename == null) return;
        String base = filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        String[] parts = base.split("\\.");
        for (int i = 1; i < parts.length; i++) if (ACTIVE_EXTENSIONS.contains(parts[i].strip())) throw active();
    }

    private String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String decodeText(byte[] bytes) {
        try {
            Charset charset = StandardCharsets.UTF_8;
            int offset = 0;
            if (bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191) offset = 3;
            else if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254) { charset = StandardCharsets.UTF_16LE; offset = 2; }
            else if (bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255) { charset = StandardCharsets.UTF_16BE; offset = 2; }
            String text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
            if (text.codePoints().anyMatch(c -> (c < 32 && c != '\t' && c != '\n' && c != '\r') || c == 127)) throw mismatch();
            return text;
        } catch (CharacterCodingException exception) { throw mismatch(); }
    }

    private InspectedType mediaType(byte[] b) {
        if (b.length >= 3 && (b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255) return new InspectedType("image/jpeg", "jpg");
        if (b.length >= 8 && (b[0] & 255) == 137 && startsAt(b, 1, "PNG\r\n\032\n")) return new InspectedType("image/png", "png");
        if (b.length >= 12 && starts(b, "RIFF") && startsAt(b, 8, "WEBP")) return new InspectedType("image/webp", "webp");
        if (b.length >= 12 && startsAt(b, 4, "ftyp")) return new InspectedType("video/mp4", "mp4");
        if (b.length >= 4 && (b[0] & 255) == 26 && (b[1] & 255) == 69 && (b[2] & 255) == 223 && (b[3] & 255) == 163) return new InspectedType("video/webm", "webm");
        return null;
    }

    private boolean isExecutable(byte[] b) {
        if (starts(b, "MZ") || starts(b, "\177ELF") || starts(b, "\0asm") || starts(b, "dex\n")) return true;
        if (b.length < 4) return false;
        long magic = u32(b, 0);
        return Set.of(0xbebafecaL, 0xcefaedfeL, 0xcffaedfeL, 0xfeedfaceL, 0xfeedfacfL, 0xcafebabeL).contains(magic)
                || (b.length >= 8 && magic == 0xe011cfd0L && u32(b, 4) == 0xe11ab1a1L); // Legacy OLE documents/macros are opaque.
    }

    private String decodePdfNames(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '#' && i + 2 < text.length()) {
                int high = Character.digit(text.charAt(i + 1), 16), low = Character.digit(text.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) { result.append((char) (high * 16 + low)); i += 2; continue; }
            }
            result.append(text.charAt(i));
        }
        return result.toString();
    }

    private String normalizeMime(String mime) { return mime == null ? "" : mime.split(";", 2)[0].strip().toLowerCase(Locale.ROOT); }
    private void requireMime(String mime, Set<String> allowed) {
        if (!mime.isEmpty() && !mime.equals("application/octet-stream") && !allowed.contains(mime)) throw mismatch();
    }
    private String officeMime(String extension) {
        return "application/vnd.openxmlformats-officedocument." + switch (extension) {
            case "docx" -> "wordprocessingml.document"; case "xlsx" -> "spreadsheetml.sheet"; default -> "presentationml.presentation";
        };
    }
    private boolean starts(byte[] bytes, String prefix) { return startsAt(bytes, 0, prefix); }
    private boolean startsAt(byte[] bytes, int offset, String value) {
        byte[] prefix = value.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[offset + i] != prefix[i]) return false;
        return true;
    }
    private int u16(byte[] b, int offset) { return (b[offset] & 255) | ((b[offset + 1] & 255) << 8); }
    private long u32(byte[] b, int offset) { return Integer.toUnsignedLong(u16(b, offset) | (u16(b, offset + 2) << 16)); }
    private BusinessException active() { return rejected("FILE_ACTIVE_CONTENT_BLOCKED", "文件包含不支持的可执行或活动内容，已拒绝保存"); }
    private BusinessException mismatch() { return rejected("FILE_TYPE_MISMATCH", "文件扩展名、声明类型与实际内容不一致"); }
    private BusinessException invalidArchive() { return rejected("ARCHIVE_CONTENT_INVALID", "压缩文档结构无效或包含不安全条目"); }
    private BusinessException archiveLimit() { return rejected("ARCHIVE_LIMIT_EXCEEDED", "压缩文档超过安全检查限制"); }
    private BusinessException rejected(String code, String message) { return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message); }
    public record InspectedType(String contentType, String extension) {}
    private record ArchiveEntry(String name, long compressedSize, long size, long crc) {}
}
