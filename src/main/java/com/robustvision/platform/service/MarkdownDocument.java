package com.robustvision.platform.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 Markdown 结构解析器：把笔记正文解析成块级元素，供 PDF / DOCX / Markdown 三种导出共用。
 *
 * 只覆盖笔记实际会用到的语法子集（标题、段落、列表、代码块、引用、表格、分隔线、行内标记），
 * 不追求 CommonMark 完整实现；无法识别的行按普通段落处理，保证不丢内容。
 */
public final class MarkdownDocument {

    private MarkdownDocument() {}

    public enum BlockType {
        HEADING, PARAGRAPH, BULLET_LIST, ORDERED_LIST, CODE_BLOCK, QUOTE, TABLE, DIVIDER
    }

    /** 一个块级元素。 */
    public record Block(BlockType type, int level, List<String> lines, List<String> meta) {}

    /** 行内片段：文本 + 样式位。 */
    public record InlineRun(String text, boolean bold, boolean italic, boolean code) {}

    public static List<Block> parse(String markdown) {
        List<Block> blocks = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) return blocks;

        String[] rawLines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> paragraph = new ArrayList<>();

        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i];
            String trimmed = line.trim();

            // 代码块
            if (trimmed.startsWith("```")) {
                flushParagraph(blocks, paragraph);
                String lang = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
                List<String> code = new ArrayList<>();
                i++;
                while (i < rawLines.length && !rawLines[i].trim().startsWith("```")) {
                    code.add(rawLines[i]);
                    i++;
                }
                blocks.add(new Block(BlockType.CODE_BLOCK, 0, code, List.of(lang)));
                continue;
            }

            // 空行：结束当前段落
            if (trimmed.isEmpty()) {
                flushParagraph(blocks, paragraph);
                continue;
            }

            // 分隔线
            if (trimmed.matches("(-{3,}|\\*{3,}|_{3,})")) {
                flushParagraph(blocks, paragraph);
                blocks.add(new Block(BlockType.DIVIDER, 0, List.of(), List.of()));
                continue;
            }

            // 标题
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && level < 6 && trimmed.charAt(level) == '#') level++;
                if (level > 0 && (level == trimmed.length() || trimmed.charAt(level) == ' ')) {
                    flushParagraph(blocks, paragraph);
                    String text = trimmed.substring(level).trim();
                    blocks.add(new Block(BlockType.HEADING, level, List.of(text), List.of()));
                    continue;
                }
            }

            // 引用
            if (trimmed.startsWith(">")) {
                flushParagraph(blocks, paragraph);
                List<String> quote = new ArrayList<>();
                while (i < rawLines.length && rawLines[i].trim().startsWith(">")) {
                    quote.add(rawLines[i].trim().replaceFirst("^>\\s?", ""));
                    i++;
                }
                i--;
                blocks.add(new Block(BlockType.QUOTE, 0, quote, List.of()));
                continue;
            }

            // 表格：当前行含 | 且下一行是分隔行
            if (trimmed.contains("|") && i + 1 < rawLines.length
                    && rawLines[i + 1].trim().matches("^\\|?[\\s:|-]+\\|?$")
                    && rawLines[i + 1].contains("-")) {
                flushParagraph(blocks, paragraph);
                List<String> rows = new ArrayList<>();
                rows.add(trimmed);
                i += 2; // 跳过分隔行
                while (i < rawLines.length && rawLines[i].trim().contains("|") && !rawLines[i].trim().isEmpty()) {
                    rows.add(rawLines[i].trim());
                    i++;
                }
                i--;
                blocks.add(new Block(BlockType.TABLE, 0, rows, List.of()));
                continue;
            }

            // 无序列表
            if (trimmed.matches("^[-*+]\\s+.*")) {
                flushParagraph(blocks, paragraph);
                List<String> items = new ArrayList<>();
                while (i < rawLines.length && rawLines[i].trim().matches("^[-*+]\\s+.*")) {
                    items.add(rawLines[i].trim().replaceFirst("^[-*+]\\s+", ""));
                    i++;
                }
                i--;
                blocks.add(new Block(BlockType.BULLET_LIST, 0, items, List.of()));
                continue;
            }

            // 有序列表
            if (trimmed.matches("^\\d+[.)]\\s+.*")) {
                flushParagraph(blocks, paragraph);
                List<String> items = new ArrayList<>();
                while (i < rawLines.length && rawLines[i].trim().matches("^\\d+[.)]\\s+.*")) {
                    items.add(rawLines[i].trim().replaceFirst("^\\d+[.)]\\s+", ""));
                    i++;
                }
                i--;
                blocks.add(new Block(BlockType.ORDERED_LIST, 0, items, List.of()));
                continue;
            }

            // 其余归入段落
            paragraph.add(trimmed);
        }
        flushParagraph(blocks, paragraph);
        return blocks;
    }

    private static void flushParagraph(List<Block> blocks, List<String> paragraph) {
        if (!paragraph.isEmpty()) {
            blocks.add(new Block(BlockType.PARAGRAPH, 0, new ArrayList<>(paragraph), List.of()));
            paragraph.clear();
        }
    }

    /** 解析表格行，去掉首尾竖线并按 | 切分。 */
    public static List<String> splitTableRow(String row) {
        String cleaned = row.trim();
        if (cleaned.startsWith("|")) cleaned = cleaned.substring(1);
        if (cleaned.endsWith("|")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : cleaned.split("\\|", -1)) cells.add(cell.trim());
        return cells;
    }

    /**
     * 解析行内标记：**粗体**、*斜体*、`代码`。
     * 链接 [text](url) 只保留 text，导出文档中不承载可点击跳转。
     */
    public static List<InlineRun> parseInline(String text) {
        List<InlineRun> runs = new ArrayList<>();
        if (text == null || text.isEmpty()) return runs;

        // 先剥离链接，保留显示文本
        String source = text.replaceAll("!\\[([^]]*)]\\([^)]*\\)", "$1")
                            .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1");

        StringBuilder buffer = new StringBuilder();
        boolean bold = false;
        boolean italic = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);

            // 行内代码
            if (c == '`') {
                int end = source.indexOf('`', i + 1);
                if (end > i) {
                    if (buffer.length() > 0) {
                        runs.add(new InlineRun(buffer.toString(), bold, italic, false));
                        buffer.setLength(0);
                    }
                    runs.add(new InlineRun(source.substring(i + 1, end), false, false, true));
                    i = end;
                    continue;
                }
            }

            // ** 粗体
            if (c == '*' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                if (buffer.length() > 0) {
                    runs.add(new InlineRun(buffer.toString(), bold, italic, false));
                    buffer.setLength(0);
                }
                bold = !bold;
                i++;
                continue;
            }

            // * 或 _ 斜体
            if ((c == '*' || c == '_') && i + 1 < source.length() && source.charAt(i + 1) != ' ') {
                if (buffer.length() > 0) {
                    runs.add(new InlineRun(buffer.toString(), bold, italic, false));
                    buffer.setLength(0);
                }
                italic = !italic;
                continue;
            }

            buffer.append(c);
        }
        if (buffer.length() > 0) runs.add(new InlineRun(buffer.toString(), bold, italic, false));
        return runs;
    }

    /** 去掉所有行内标记，返回纯文本（用于 DOCX 简单段落与摘要）。 */
    public static String plainText(String text) {
        StringBuilder builder = new StringBuilder();
        for (InlineRun run : parseInline(text)) builder.append(run.text());
        return builder.toString();
    }
}
