package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.dto.ApiDtos;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class LogExportService {
    private final ObjectMapper objectMapper;
    public LogExportService(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public ExportFile export(List<ApiDtos.InferenceView> tasks, String requestedFormat) {
        String format = requestedFormat == null ? "json" : requestedFormat.toLowerCase();
        try {
            return switch (format) {
                case "json" -> new ExportFile(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(tasks), "application/json", "inference-logs.json");
                case "csv" -> new ExportFile(csv(tasks), "text/csv; charset=UTF-8", "inference-logs.csv");
                case "pdf" -> new ExportFile(pdf(tasks), "application/pdf", "inference-logs.pdf");
                case "docx", "word" -> new ExportFile(docx(tasks), "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "inference-logs.docx");
                default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "EXPORT_FORMAT_INVALID", "仅支持 JSON、CSV、PDF 或 Word(DOCX)");
            };
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "LOG_EXPORT_FAILED", "日志导出失败"); }
    }

    private byte[] csv(List<ApiDtos.InferenceView> tasks) {
        StringBuilder csv = new StringBuilder("\uFEFFid,traceId,status,provider,model,taskType,requestedBy,inputFile,costCny,createdAt,completedAt,error\r\n");
        tasks.forEach(task -> csv.append(row(task)).append("\r\n"));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    private String row(ApiDtos.InferenceView task) {
        return String.join(",", quote(task.id()), quote(task.traceId()), quote(task.status().name()), quote(task.provider().name()),
                quote(task.model().name()), quote(task.taskType().name()), quote(task.requestedBy()), quote(task.inputFile().originalName()),
                quote(String.valueOf(task.costCny())), quote(String.valueOf(task.createdAt())), quote(String.valueOf(task.completedAt())), quote(task.errorMessage()));
    }
    private String quote(String value) { return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\""; }

    private byte[] pdf(List<ApiDtos.InferenceView> tasks) {
        List<String> lines = new ArrayList<>();
        lines.add("Personal Platform - Inference Logs"); lines.add("Generated: " + Instant.now()); lines.add("Records: " + tasks.size()); lines.add("");
        tasks.stream().limit(44).forEach(task -> lines.add(task.createdAt() + " | " + task.status() + " | " + task.provider() + " | " + task.traceId()));
        if (tasks.size() > 44) lines.add("More records are available in JSON or CSV export.");
        StringBuilder content = new StringBuilder("BT /F1 9 Tf 40 805 Td 12 TL ");
        for (String line : lines) content.append('(').append(pdfText(line)).append(") Tj T* ");
        content.append("ET"); byte[] stream = content.toString().getBytes(StandardCharsets.US_ASCII);
        List<byte[]> objects = List.of(
                bytes("<< /Type /Catalog /Pages 2 0 R >>"),
                bytes("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"),
                bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"),
                concat(bytes("<< /Length " + stream.length + " >>\nstream\n"), stream, bytes("\nendstream"))
        );
        ByteArrayOutputStream out = new ByteArrayOutputStream(); write(out, bytes("%PDF-1.4\n")); List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) { offsets.add(out.size()); write(out, bytes((i + 1) + " 0 obj\n")); write(out, objects.get(i)); write(out, bytes("\nendobj\n")); }
        int xref = out.size(); write(out, bytes("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n"));
        offsets.forEach(offset -> write(out, bytes("%010d 00000 n \n".formatted(offset))));
        write(out, bytes("trailer << /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF")); return out.toByteArray();
    }
    private String pdfText(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toCharArray()) result.append(c >= 32 && c <= 126 ? c : '?');
        return result.toString().replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private byte[] docx(List<ApiDtos.InferenceView> tasks) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append(paragraph("Personal Platform 推理日志")).append(paragraph("生成时间：" + Instant.now())).append(paragraph("记录数：" + tasks.size()));
        tasks.forEach(task -> body.append(paragraph(task.createdAt() + "｜" + task.status() + "｜" + task.provider() + "｜" + task.model().name() + "｜traceId=" + task.traceId())));
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body + "<w:sectPr/></w:body></w:document>";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            zip(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            zip(zip, "word/document.xml", document);
        }
        return bytes.toByteArray();
    }
    private String paragraph(String text) { return "<w:p><w:r><w:t xml:space=\"preserve\">" + xml(text) + "</w:t></w:r></w:p>"; }
    private String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private void zip(ZipOutputStream zip, String name, String value) throws Exception { zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private byte[] concat(byte[]... values) { ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] value : values) write(out, value); return out.toByteArray(); }
    private void write(ByteArrayOutputStream out, byte[] value) { out.write(value, 0, value.length); }
    public record ExportFile(byte[] content, String contentType, String fileName) {}
}
