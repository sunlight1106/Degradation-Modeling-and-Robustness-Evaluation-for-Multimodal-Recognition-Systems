package com.robustvision.platform.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;

/** Offline, allowlisted HTML conversion. Never loads URLs or executes note content. */
final class NoteContent {
    private NoteContent() {}

    static String safeHtml(String source) {
        return Jsoup.clean(source == null ? "" : source, new Safelist()
                .addTags("p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b", "em", "i",
                        "ul", "ol", "li", "blockquote", "pre", "code", "hr", "table", "thead", "tbody", "tr", "th", "td", "a", "div", "span")
                .addAttributes("a", "href").addProtocols("a", "href", "https", "http", "mailto"));
    }

    static String htmlToMarkdown(String source) {
        return markdown(Jsoup.parseBodyFragment(safeHtml(source)).body(), 0).trim();
    }

    private static String markdown(Node node, int depth) {
        if (node instanceof TextNode text) return text.getWholeText();
        if (!(node instanceof Element el)) return "";
        if (depth > 100) return el.text();
        String tag = el.normalName();
        if (tag.equals("pre")) return "\n\n```\n" + el.wholeText().replace("```", "` ` `") + "\n```\n\n";
        if (tag.equals("table")) {
            StringBuilder table = new StringBuilder("\n\n");
            int row = 0;
            for (Element tr : el.select("tr")) {
                var cells = tr.select("th,td");
                table.append("| ");
                for (Element cell : cells) table.append(cell.text().replace('|', '／')).append(" | ");
                table.append('\n');
                if (row++ == 0) table.append("| --- ".repeat(cells.size())).append("|\n");
            }
            return table.append('\n').toString();
        }
        StringBuilder children = new StringBuilder();
        for (Node child : el.childNodes()) children.append(markdown(child, depth + 1));
        String content = children.toString();
        if (tag.matches("h[1-6]")) return "\n\n" + "#".repeat(tag.charAt(1) - '0') + " " + content.strip() + "\n\n";
        return switch (tag) {
            case "br" -> "\n";
            case "hr" -> "\n\n---\n\n";
            case "strong", "b" -> "**" + content + "**";
            case "em", "i" -> "*" + content + "*";
            case "code" -> "`" + content + "`";
            case "a" -> el.hasAttr("href") ? "[" + content + "](" + el.attr("href").replace(")", "%29") + ")" : content;
            case "li" -> (el.parent() != null && el.parent().normalName().equals("ol") ? "1. " : "- ") + content.strip() + "\n";
            case "blockquote" -> "\n\n> " + content.strip().replace("\n", "\n> ") + "\n\n";
            case "p", "div", "ul", "ol" -> "\n\n" + content.strip() + "\n\n";
            default -> content;
        };
    }

    static String markdownToHtml(String source) {
        StringBuilder html = new StringBuilder();
        for (var block : MarkdownDocument.parse(source)) {
            switch (block.type()) {
                case HEADING -> html.append("<h").append(block.level()).append('>').append(inline(block.lines().get(0))).append("</h").append(block.level()).append('>');
                case CODE_BLOCK -> html.append("<pre><code>").append(escape(String.join("\n", block.lines()))).append("</code></pre>");
                case DIVIDER -> html.append("<hr>");
                case TABLE -> {
                    html.append("<table>");
                    for (String row : block.lines()) {
                        html.append("<tr>");
                        for (String cell : MarkdownDocument.splitTableRow(row)) html.append("<td>").append(inline(cell)).append("</td>");
                        html.append("</tr>");
                    }
                    html.append("</table>");
                }
                default -> {
                    String tag = switch (block.type()) { case BULLET_LIST -> "ul"; case ORDERED_LIST -> "ol"; case QUOTE -> "blockquote"; default -> "p"; };
                    boolean list = tag.equals("ul") || tag.equals("ol");
                    html.append('<').append(tag).append('>');
                    for (String line : block.lines()) html.append(list ? "<li>" : "").append(inline(line)).append(list ? "</li>" : "<br>");
                    html.append("</").append(tag).append('>');
                }
            }
        }
        return html.toString();
    }

    private static String inline(String source) {
        StringBuilder html = new StringBuilder();
        for (var run : MarkdownDocument.parseInline(source)) {
            String value = escape(run.text());
            if (run.code()) value = "<code>" + value + "</code>";
            if (run.bold()) value = "<strong>" + value + "</strong>";
            if (run.italic()) value = "<em>" + value + "</em>";
            html.append(value);
        }
        return html.toString();
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
