package com.robustvision.platform.service;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.dto.ApiDtos;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 笔记导出服务：Markdown / PDF / DOCX 三种格式共用 MarkdownDocument 的解析结果。
 *
 * PDF 未复用 LogExportService：那份实现手写 PDF 字节流且 BaseFont 固定 Helvetica，
 * 其 pdfText() 会把非 ASCII 字符替换为 '?'，中文笔记导出后会全是问号。
 * 这里改用 OpenPDF + STSong-Light（UniGB-UCS2-H 编码）渲染中文。
 */
@Service
public class NoteExportService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)
            .withZone(ZoneId.systemDefault());

    public record ExportFile(byte[] content, String contentType, String fileName) {}

    public ExportFile export(ApiDtos.NoteView note, String requestedFormat) {
        String format = requestedFormat == null ? "md" : requestedFormat.trim().toLowerCase(Locale.ROOT);
        String baseName = safeFileName(note.title());
        return switch (format) {
            case "md", "markdown" -> new ExportFile(
                    markdown(note), "text/markdown; charset=UTF-8", baseName + ".md");
            case "pdf" -> new ExportFile(pdf(note), "application/pdf", baseName + ".pdf");
            case "docx", "word" -> new ExportFile(
                    docx(note),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    baseName + ".docx");
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTE_EXPORT_FORMAT_INVALID",
                    "仅支持 Markdown(md)、PDF 或 Word(docx)");
        };
    }

    /** 文件名去掉路径分隔符与控制字符，避免响应头被注入。 */
    static String safeFileName(String title) {
        String cleaned = (title == null || title.isBlank() ? "note" : title.trim())
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) cleaned = "note";
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    // ------------------------------------------------------------------
    // Markdown
    // ------------------------------------------------------------------

    private byte[] markdown(ApiDtos.NoteView note) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(note.title()).append("\n\n");
        builder.append("> 导出自 Personal Platform 知识库 · ")
               .append(STAMP.format(Instant.now())).append("\n");
        if (note.tags() != null && !note.tags().isEmpty()) {
            builder.append("> 标签：").append(String.join("、", note.tags())).append("\n");
        }
        builder.append("\n---\n\n");
        builder.append(note.body() == null ? "" : note.body().trim()).append("\n");

        List<ApiDtos.NoteReferenceView> references = note.references();
        if (references != null && !references.isEmpty()) {
            builder.append("\n---\n\n## 引用\n\n");
            for (ApiDtos.NoteReferenceView reference : references) {
                builder.append("- **").append(typeLabel(reference.referenceType())).append("** ")
                       .append(reference.displayTitle());
                if (reference.displayMeta() != null && !reference.displayMeta().isBlank()) {
                    builder.append("（").append(reference.displayMeta()).append("）");
                }
                if (!reference.accessible()) builder.append(" ⚠️ 引用目标已删除");
                builder.append("\n");
            }
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // PDF
    // ------------------------------------------------------------------

    private byte[] pdf(ApiDtos.NoteView note) {
        try {
            BaseFont baseFont = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font titleFont = new Font(baseFont, 19, Font.BOLD, new Color(0x1f, 0x2a, 0x26));
            Font h1 = new Font(baseFont, 15, Font.BOLD, new Color(0x2c, 0x4a, 0x3e));
            Font h2 = new Font(baseFont, 13, Font.BOLD, new Color(0x2c, 0x4a, 0x3e));
            Font h3 = new Font(baseFont, 11.5f, Font.BOLD, new Color(0x33, 0x33, 0x30));
            Font body = new Font(baseFont, 10.5f, Font.NORMAL, new Color(0x22, 0x22, 0x20));
            Font boldBody = new Font(baseFont, 10.5f, Font.BOLD, new Color(0x22, 0x22, 0x20));
            Font italicBody = new Font(baseFont, 10.5f, Font.ITALIC, new Color(0x22, 0x22, 0x20));
            Font codeFont = new Font(baseFont, 9.5f, Font.NORMAL, new Color(0x3a, 0x3a, 0x36));
            Font metaFont = new Font(baseFont, 9f, Font.NORMAL, new Color(0x77, 0x77, 0x70));
            Font cellFont = new Font(baseFont, 9.5f, Font.NORMAL, new Color(0x22, 0x22, 0x20));
            Font cellHead = new Font(baseFont, 9.5f, Font.BOLD, Color.WHITE);

            Document document = new Document(PageSize.A4, 46, 46, 52, 46);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(document, out);
            document.open();

            Paragraph title = new Paragraph(note.title(), titleFont);
            title.setSpacingAfter(6f);
            document.add(title);

            StringBuilder meta = new StringBuilder("Personal Platform 知识库导出 · ").append(STAMP.format(Instant.now()));
            if (note.tags() != null && !note.tags().isEmpty()) {
                meta.append(" · 标签：").append(String.join("、", note.tags()));
            }
            Paragraph metaParagraph = new Paragraph(meta.toString(), metaFont);
            metaParagraph.setSpacingAfter(14f);
            document.add(metaParagraph);

            for (MarkdownDocument.Block block : MarkdownDocument.parse(note.body())) {
                switch (block.type()) {
                    case HEADING -> {
                        Font font = switch (block.level()) {
                            case 1 -> h1;
                            case 2 -> h2;
                            default -> h3;
                        };
                        Paragraph heading = new Paragraph(
                                MarkdownDocument.plainText(block.lines().get(0)), font);
                        heading.setSpacingBefore(block.level() == 1 ? 14f : 10f);
                        heading.setSpacingAfter(5f);
                        document.add(heading);
                    }
                    case PARAGRAPH -> {
                        Paragraph paragraph = new Paragraph();
                        for (String line : block.lines()) appendInline(paragraph, line, body, boldBody, italicBody, codeFont);
                        paragraph.setSpacingAfter(6f);
                        paragraph.setAlignment(Element.ALIGN_JUSTIFIED);
                        document.add(paragraph);
                    }
                    case BULLET_LIST -> {
                        for (String item : block.lines()) {
                            Paragraph paragraph = new Paragraph();
                            paragraph.setIndentationLeft(16f);
                            paragraph.setFirstLineIndent(-10f);
                            paragraph.add(new Chunk("•  ", body));
                            appendInline(paragraph, item, body, boldBody, italicBody, codeFont);
                            paragraph.setSpacingAfter(3f);
                            document.add(paragraph);
                        }
                        addSpacer(document);
                    }
                    case ORDERED_LIST -> {
                        int index = 1;
                        for (String item : block.lines()) {
                            Paragraph paragraph = new Paragraph();
                            paragraph.setIndentationLeft(18f);
                            paragraph.setFirstLineIndent(-14f);
                            paragraph.add(new Chunk(index + ".  ", body));
                            appendInline(paragraph, item, body, boldBody, italicBody, codeFont);
                            paragraph.setSpacingAfter(3f);
                            document.add(paragraph);
                            index++;
                        }
                        addSpacer(document);
                    }
                    case QUOTE -> {
                        for (String line : block.lines()) {
                            Paragraph paragraph = new Paragraph(MarkdownDocument.plainText(line), italicBody);
                            paragraph.setIndentationLeft(14f);
                            paragraph.setSpacingAfter(3f);
                            document.add(paragraph);
                        }
                        addSpacer(document);
                    }
                    case CODE_BLOCK -> {
                        PdfPTable table = new PdfPTable(1);
                        table.setWidthPercentage(100f);
                        PdfPCell cell = new PdfPCell(new Phrase(
                                String.join("\n", block.lines()), codeFont));
                        cell.setBackgroundColor(new Color(0xf4, 0xf4, 0xf0));
                        cell.setBorderColor(new Color(0xdd, 0xdd, 0xd6));
                        cell.setPadding(8f);
                        table.addCell(cell);
                        document.add(table);
                        addSpacer(document);
                    }
                    case TABLE -> document.add(buildPdfTable(block.lines(), cellFont, cellHead));
                    case DIVIDER -> {
                        Paragraph divider = new Paragraph("— — —", metaFont);
                        divider.setAlignment(Element.ALIGN_CENTER);
                        divider.setSpacingBefore(8f);
                        divider.setSpacingAfter(8f);
                        document.add(divider);
                    }
                }
            }

            List<ApiDtos.NoteReferenceView> references = note.references();
            if (references != null && !references.isEmpty()) {
                Paragraph heading = new Paragraph("引用", h2);
                heading.setSpacingBefore(16f);
                heading.setSpacingAfter(6f);
                document.add(heading);
                for (ApiDtos.NoteReferenceView reference : references) {
                    Paragraph paragraph = new Paragraph();
                    paragraph.setIndentationLeft(16f);
                    paragraph.setFirstLineIndent(-10f);
                    paragraph.add(new Chunk("•  ", body));
                    paragraph.add(new Chunk(typeLabel(reference.referenceType()) + "：", boldBody));
                    paragraph.add(new Chunk(reference.displayTitle(), body));
                    if (reference.displayMeta() != null && !reference.displayMeta().isBlank()) {
                        paragraph.add(new Chunk("（" + reference.displayMeta() + "）", metaFont));
                    }
                    if (!reference.accessible()) {
                        paragraph.add(new Chunk(" 引用目标已删除", metaFont));
                    }
                    paragraph.setSpacingAfter(3f);
                    document.add(paragraph);
                }
            }

            document.close();
            return out.toByteArray();
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "NOTE_PDF_EXPORT_FAILED",
                    "PDF 导出失败，请重试或改用 Markdown 导出");
        }
    }

    private void appendInline(Paragraph paragraph, String line, Font normal, Font bold,
                              Font italic, Font code) {
        for (MarkdownDocument.InlineRun run : MarkdownDocument.parseInline(line)) {
            Font font = run.code() ? code : (run.bold() ? bold : (run.italic() ? italic : normal));
            paragraph.add(new Chunk(run.text(), font));
        }
        paragraph.add(new Chunk("\n", normal));
    }

    private void addSpacer(Document document) {
        Paragraph spacer = new Paragraph(" ", new Font(Font.HELVETICA, 4));
        spacer.setSpacingAfter(2f);
        document.add(spacer);
    }

    private PdfPTable buildPdfTable(List<String> rows, Font cellFont, Font cellHead) {
        List<String> headerCells = MarkdownDocument.splitTableRow(rows.get(0));
        int columns = Math.max(1, headerCells.size());
        PdfPTable table = new PdfPTable(columns);
        table.setWidthPercentage(100f);
        table.setSpacingBefore(6f);
        table.setSpacingAfter(8f);

        for (String header : headerCells) {
            PdfPCell cell = new PdfPCell(new Phrase(MarkdownDocument.plainText(header), cellHead));
            cell.setBackgroundColor(new Color(0x3d, 0x5f, 0x52));
            cell.setPadding(5f);
            cell.setBorderColor(new Color(0xcc, 0xcc, 0xc4));
            table.addCell(cell);
        }
        boolean zebra = false;
        for (int i = 1; i < rows.size(); i++) {
            List<String> cells = MarkdownDocument.splitTableRow(rows.get(i));
            for (int c = 0; c < columns; c++) {
                String value = c < cells.size() ? MarkdownDocument.plainText(cells.get(c)) : "";
                PdfPCell cell = new PdfPCell(new Phrase(value, cellFont));
                cell.setPadding(5f);
                cell.setBorderColor(new Color(0xdd, 0xdd, 0xd6));
                if (zebra) cell.setBackgroundColor(new Color(0xf7, 0xf7, 0xf3));
                table.addCell(cell);
            }
            zebra = !zebra;
        }
        return table;
    }

    // ------------------------------------------------------------------
    // DOCX
    // ------------------------------------------------------------------

    private byte[] docx(ApiDtos.NoteView note) {
        try {
            StringBuilder body = new StringBuilder();

            body.append(run(note.title(), 40, true, false, "2F4F43", 240));

            StringBuilder meta = new StringBuilder("Personal Platform 知识库导出 · ")
                    .append(STAMP.format(Instant.now()));
            if (note.tags() != null && !note.tags().isEmpty()) {
                meta.append(" · 标签：").append(String.join("、", note.tags()));
            }
            body.append(run(meta.toString(), 18, false, true, "7A7A72", 180));

            for (MarkdownDocument.Block block : MarkdownDocument.parse(note.body())) {
                switch (block.type()) {
                    case HEADING -> {
                        int size = switch (block.level()) {
                            case 1 -> 32;
                            case 2 -> 27;
                            default -> 23;
                        };
                        body.append(run(MarkdownDocument.plainText(block.lines().get(0)),
                                size, true, false, "2F4F43", 160));
                    }
                    case PARAGRAPH -> {
                        StringBuilder text = new StringBuilder();
                        for (int i = 0; i < block.lines().size(); i++) {
                            if (i > 0) text.append(' ');
                            text.append(MarkdownDocument.plainText(block.lines().get(i)));
                        }
                        body.append(paragraph(text.toString(), 21, false, 120));
                    }
                    case BULLET_LIST -> {
                        for (String item : block.lines()) {
                            body.append(indentedParagraph("•  " + MarkdownDocument.plainText(item), 21, 360));
                        }
                    }
                    case ORDERED_LIST -> {
                        int index = 1;
                        for (String item : block.lines()) {
                            body.append(indentedParagraph(index + ".  " + MarkdownDocument.plainText(item), 21, 360));
                            index++;
                        }
                    }
                    case QUOTE -> {
                        for (String line : block.lines()) {
                            body.append(indentedParagraph(MarkdownDocument.plainText(line), 21, 360, true, "5C6B63"));
                        }
                    }
                    case CODE_BLOCK -> {
                        for (String line : block.lines()) {
                            body.append(shadedParagraph(line.isEmpty() ? " " : line));
                        }
                    }
                    case TABLE -> body.append(docxTable(block.lines()));
                    case DIVIDER -> body.append(paragraph("— — —", 18, true, 120));
                }
            }

            List<ApiDtos.NoteReferenceView> references = note.references();
            if (references != null && !references.isEmpty()) {
                body.append(run("引用", 27, true, false, "2F4F43", 200));
                for (ApiDtos.NoteReferenceView reference : references) {
                    StringBuilder line = new StringBuilder("•  ")
                            .append(typeLabel(reference.referenceType())).append("：")
                            .append(reference.displayTitle());
                    if (reference.displayMeta() != null && !reference.displayMeta().isBlank()) {
                        line.append("（").append(reference.displayMeta()).append("）");
                    }
                    if (!reference.accessible()) line.append(" 引用目标已删除");
                    body.append(indentedParagraph(line.toString(), 21, 360));
                }
            }

            String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body>" + body
                    + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                    + "<w:pgMar w:top=\"1440\" w:right=\"1200\" w:bottom=\"1440\" w:left=\"1200\" "
                    + "w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>"
                    + "</w:body></w:document>";

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                zipEntry(zip, "[Content_Types].xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                        + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                        + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                        + "</Types>");
                zipEntry(zip, "_rels/.rels",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                        + "</Relationships>");
                zipEntry(zip, "word/document.xml", document);
            }
            return bytes.toByteArray();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "NOTE_DOCX_EXPORT_FAILED",
                    "Word 导出失败，请重试或改用 Markdown 导出");
        }
    }

    /** 带标题级间距的段落（用于标题）。 */
    private String run(String text, int halfPointSize, boolean bold,
                       boolean italic, String color, int spaceBefore) {
        return "<w:p><w:pPr><w:spacing w:before=\"" + spaceBefore + "\" w:after=\"80\"/></w:pPr>"
                + textRun(text, halfPointSize, bold, italic, color, null)
                + "</w:p>";
    }

    private String paragraph(String text, int halfPointSize, boolean center, int spaceAfter) {
        String alignment = center ? "<w:jc w:val=\"center\"/>" : "";
        return "<w:p><w:pPr><w:spacing w:after=\"" + spaceAfter + "\"/>" + alignment + "</w:pPr>"
                + textRun(text, halfPointSize, false, false, "222220", null)
                + "</w:p>";
    }

    private String indentedParagraph(String text, int halfPointSize, int indent) {
        return indentedParagraph(text, halfPointSize, indent, false, "222220");
    }

    private String indentedParagraph(String text, int halfPointSize, int indent,
                                     boolean italic, String color) {
        return "<w:p><w:pPr><w:spacing w:after=\"60\"/>"
                + "<w:ind w:left=\"" + indent + "\"/></w:pPr>"
                + textRun(text, halfPointSize, false, italic, color, null)
                + "</w:p>";
    }

    private String shadedParagraph(String text) {
        return "<w:p><w:pPr><w:spacing w:after=\"0\"/>"
                + "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F4F4F0\"/>"
                + "<w:ind w:left=\"120\" w:right=\"120\"/></w:pPr>"
                + textRun(text, 19, false, false, "3A3A36", "Consolas")
                + "</w:p>";
    }

    private String textRun(String text, int halfPointSize, boolean bold, boolean italic,
                           String color, String fontName) {
        StringBuilder properties = new StringBuilder();
        properties.append("<w:sz w:val=\"").append(halfPointSize).append("\"/>");
        properties.append("<w:szCs w:val=\"").append(halfPointSize).append("\"/>");
        properties.append("<w:color w:val=\"").append(color).append("\"/>");
        if (bold) properties.append("<w:b/>");
        if (italic) properties.append("<w:i/>");
        if (fontName != null) {
            properties.append("<w:rFonts w:ascii=\"").append(fontName)
                      .append("\" w:hAnsi=\"").append(fontName).append("\"/>");
        } else {
            properties.append("<w:rFonts w:ascii=\"Microsoft YaHei\" w:hAnsi=\"Microsoft YaHei\" w:eastAsia=\"Microsoft YaHei\"/>");
        }
        return "<w:r><w:rPr>" + properties + "</w:rPr>"
                + "<w:t xml:space=\"preserve\">" + xml(text) + "</w:t></w:r>";
    }

    private String docxTable(List<String> rows) {
        List<String> headerCells = MarkdownDocument.splitTableRow(rows.get(0));
        int columns = Math.max(1, headerCells.size());

        StringBuilder table = new StringBuilder();
        table.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/>")
             .append("<w:tblW w:w=\"5000\" w:type=\"pct\"/>")
             .append("<w:tblBorders>")
             .append(border("top")).append(border("left")).append(border("bottom"))
             .append(border("right")).append(border("insideH")).append(border("insideV"))
             .append("</w:tblBorders></w:tblPr>");

        table.append("<w:tr>");
        for (String header : headerCells) {
            table.append("<w:tc><w:tcPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"3D5F52\"/></w:tcPr>")
                 .append("<w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr>")
                 .append(textRun(MarkdownDocument.plainText(header), 19, true, false, "FFFFFF", null))
                 .append("</w:p></w:tc>");
        }
        for (int c = headerCells.size(); c < columns; c++) {
            table.append("<w:tc><w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr>")
                 .append(textRun("", 19, true, false, "FFFFFF", null)).append("</w:p></w:tc>");
        }
        table.append("</w:tr>");

        for (int i = 1; i < rows.size(); i++) {
            List<String> cells = MarkdownDocument.splitTableRow(rows.get(i));
            table.append("<w:tr>");
            for (int c = 0; c < columns; c++) {
                String value = c < cells.size() ? MarkdownDocument.plainText(cells.get(c)) : "";
                table.append("<w:tc><w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr>")
                     .append(textRun(value, 19, false, false, "222220", null))
                     .append("</w:p></w:tc>");
            }
            table.append("</w:tr>");
        }
        table.append("</w:tbl><w:p><w:pPr><w:spacing w:after=\"80\"/></w:pPr></w:p>");
        return table.toString();
    }

    private String border(String edge) {
        return "<w:" + edge + " w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"CCCCCC\"/>";
    }

    private void zipEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String xml(String value) {
        return (value == null ? "" : value)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private String typeLabel(String referenceType) {
        if (referenceType == null) return "引用";
        return switch (referenceType) {
            case "FILE" -> "文件";
            case "TASK" -> "推理任务";
            case "ENTRY" -> "知识卡";
            default -> "引用";
        };
    }

    /** 供分享服务复用的时间格式化。 */
    static String formatInstant(Instant instant) {
        return instant == null ? "—" : STAMP.format(instant);
    }
}
