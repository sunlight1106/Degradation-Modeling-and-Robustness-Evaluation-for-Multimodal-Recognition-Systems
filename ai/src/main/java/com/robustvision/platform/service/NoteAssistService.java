package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.dto.ApiDtos;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Explicit local-rule note helper; remote BYOK requests use PersonalAiService after a consent preview. */
@Service
public class NoteAssistService {

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final int MAX_BODY_CHARS = 24000;

    public ApiDtos.NoteAssistResponse assist(ApiDtos.NoteAssistRequest request) {
        String traceId = MDC.get("traceId");
        String action = request.action() == null ? "" : request.action().trim().toLowerCase(Locale.ROOT);
        if (!Set.of("summarize", "outline", "tags", "tidy").contains(action)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ASSIST_ACTION_INVALID",
                    "整理动作只能是 summarize、outline、tags 或 tidy");
        }
        String body = request.body() == null ? "" : request.body();
        if (body.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ASSIST_BODY_EMPTY", "笔记内容为空，无法整理");
        }
        if (body.length() > MAX_BODY_CHARS) throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE,
                "ASSIST_BODY_TOO_LARGE", "单次本地整理最多 24000 个字符，请减少内容后重试；不会截断正文");

        // This legacy route is explicitly local-only. Remote BYOK execution requires a consent preview.
        return local(action, body, traceId, null);
    }

    // ------------------------------------------------------------------
    // 本地规则实现
    // ------------------------------------------------------------------

    private ApiDtos.NoteAssistResponse local(String action, String body, String traceId, String note) {
        String result = switch (action) {
            case "summarize" -> localSummarize(body);
            case "outline" -> localOutline(body);
            case "tags" -> String.join(", ", localTags(body));
            case "tidy" -> localTidy(body);
            default -> body;
        };
        String prefix = note == null ? "" : note + " ";
        String engineNote = prefix + "结果由本地规则算法生成（engine=LOCAL_RULES），未调用任何模型。"
                + explainAction(action);
        return new ApiDtos.NoteAssistResponse(action, "LOCAL_RULES", result.trim(),
                "tags".equals(action) ? splitTags(result)
                        : ("outline".equals(action) ? splitLines(result) : List.of()),
                engineNote, traceId);
    }

    /** 明确说明每种动作的本地算法边界，避免用户误以为是模型润色。 */
    private String explainAction(String action) {
        return switch (action) {
            case "summarize" -> " 摘要为抽取式：按句子位置、长度与关键词密度打分后取前几句，不改写原文。";
            case "outline" -> " 大纲直接来自 Markdown 标题层级，结果确定可靠。";
            case "tags" -> " 标签来自内置学科词典匹配与词频统计，可能漏掉未收录的新概念。";
            case "tidy" -> " 这是格式整理（规范标点、修正 Markdown 语法、压缩空行），不做语义润色，不改写句子。";
            default -> "";
        };
    }

    /** 抽取式摘要：句子打分取 Top-N，保持原文顺序。 */
    private String localSummarize(String body) {
        List<String> sentences = splitSentences(stripMarkdown(body));
        if (sentences.isEmpty()) return "（笔记内容过短，无法生成摘要）";
        if (sentences.size() <= 3) return String.join("", sentences);

        Map<String, Integer> frequency = termFrequency(body);
        int total = sentences.size();
        record Scored(int index, String sentence, double score) {}
        List<Scored> scored = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            String sentence = sentences.get(i);
            int length = sentence.trim().length();
            if (length < 8) continue;

            double score = 0;
            for (String term : tokenize(sentence)) {
                score += frequency.getOrDefault(term, 0);
            }
            score = score / Math.max(1, tokenize(sentence).size());

            // 位置权重：开头与结尾更重要
            double position = i < 2 ? 1.35 : (i >= total - 2 ? 1.15 : 1.0);
            score *= position;

            // 过长句子适度惩罚，避免摘要臃肿
            if (length > 120) score *= 0.85;

            scored.add(new Scored(i, sentence.trim(), score));
        }
        if (scored.isEmpty()) return String.join("", sentences.subList(0, Math.min(3, total)));

        int pick = Math.min(4, Math.max(2, total / 4));
        List<Scored> top = scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(pick)
                .sorted(Comparator.comparingInt(Scored::index))
                .toList();

        return String.join("", top.stream().map(item -> item.sentence() + "。").toList());
    }

    /** 大纲：直接解析 Markdown 标题层级。 */
    private String localOutline(String body) {
        List<String> lines = new ArrayList<>();
        int headings = 0;
        for (String raw : body.replace("\r\n", "\n").split("\n")) {
            String trimmed = raw.trim();
            if (!trimmed.startsWith("#")) continue;
            int level = 0;
            while (level < trimmed.length() && level < 6 && trimmed.charAt(level) == '#') level++;
            if (level == 0 || (level < trimmed.length() && trimmed.charAt(level) != ' ')) continue;
            String text = MarkdownDocument.plainText(trimmed.substring(level).trim());
            if (text.isBlank()) continue;
            lines.add("  ".repeat(level - 1) + "- " + text);
            headings++;
            if (headings >= 60) break;
        }
        if (lines.isEmpty()) {
            return "（未检测到 Markdown 标题。使用 # ## ### 标记章节后即可生成大纲）";
        }
        return String.join("\n", lines);
    }

    /** 学科词典匹配 + 词频统计。 */
    private List<String> localTags(String body) {
        Map<String, Integer> frequency = termFrequency(body);
        Set<String> tags = new LinkedHashSet<>();

        // 1. 学科词典命中
        for (Map.Entry<String, List<String>> domain : DOMAIN_LEXICON.entrySet()) {
            int hits = 0;
            for (String term : domain.getValue()) {
                if (body.contains(term)) hits++;
            }
            if (hits >= 2) tags.add(domain.getKey());
        }

        // 2. 高频实词
        frequency.entrySet().stream()
                .filter(entry -> entry.getKey().length() >= 2)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8)
                .forEach(entry -> {
                    if (tags.size() < 6) tags.add(entry.getKey());
                });

        if (tags.isEmpty()) tags.add("未分类");
        return tags.stream().limit(6).toList();
    }

    /**
     * 格式整理：只做确定性的规范化，不改写句子语义。
     * 明确不做同义替换、不重组句式——那属于模型能力，本地不冒充。
     */
    private String localTidy(String body) {
        String text = body.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder result = new StringBuilder();
        boolean inCodeBlock = false;
        int blankRun = 0;

        for (String raw : text.split("\n", -1)) {
            String line = raw.stripTrailing();
            if (line.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                result.append(line.trim()).append('\n');
                blankRun = 0;
                continue;
            }
            if (inCodeBlock) {
                result.append(line).append('\n');
                continue;
            }

            // 压缩连续空行为一个
            if (line.trim().isEmpty()) {
                blankRun++;
                if (blankRun <= 1) result.append('\n');
                continue;
            }
            blankRun = 0;

            String cleaned = line;
            // 标题 # 后补空格
            cleaned = cleaned.replaceAll("^(#{1,6})(?! )", "$1 ");
            // 列表符号统一为 -，并保证后面有空格
            cleaned = cleaned.replaceAll("^\\s*[*+](?=\\s)", "  -");
            cleaned = cleaned.replaceAll("^\\s*-\\s*", "- ");
            // 中英文之间补空格（已有空格则不重复）
            cleaned = cleaned.replaceAll("([\\u4e00-\\u9fff])([A-Za-z0-9])", "$1 $2");
            cleaned = cleaned.replaceAll("([A-Za-z0-9])([\\u4e00-\\u9fff])", "$1 $2");
            // 中文语境下的全角标点规范化
            cleaned = cleaned.replaceAll("([\\u4e00-\\u9fff]),(?=[\\u4e00-\\u9fff])", "$1，");
            cleaned = cleaned.replaceAll("([\\u4e00-\\u9fff]);(?=[\\u4e00-\\u9fff])", "$1；");
            cleaned = cleaned.replaceAll("([\\u4e00-\\u9fff]):(?=[\\u4e00-\\u9fff])", "$1：");
            cleaned = cleaned.replaceAll("([\\u4e00-\\u9fff])\\((?=[^)]*[\\u4e00-\\u9fff])", "$1（");
            cleaned = cleaned.replaceAll("(?<=[\\u4e00-\\u9fff])\\)", "）");
            // 去掉行尾多余空格
            cleaned = cleaned.stripTrailing();

            result.append(cleaned).append('\n');
        }
        return result.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    // ------------------------------------------------------------------
    // 文本处理工具
    // ------------------------------------------------------------------

    /** 学科词典：命中 2 个以上词条才认定为该领域，降低误标。 */
    private static final Map<String, List<String>> DOMAIN_LEXICON = Map.of(
            "生物化学", List.of("酶", "蛋白质", "氨基酸", "代谢", "ATP", "辅酶", "糖酵解",
                    "三羧酸循环", "氧化磷酸化", "底物", "催化", "变性", "构象", "肽键"),
            "分子生物学", List.of("DNA", "RNA", "转录", "翻译", "复制", "基因", "启动子",
                    "外显子", "内含子", "PCR", "质粒", "核糖体", "mRNA", "突变"),
            "医学", List.of("临床", "诊断", "症状", "病理", "治疗", "剂量", "血浆", "血清",
                    "指标", "参考区间", "炎症", "免疫", "受体", "药代动力学"),
            "有机化学", List.of("官能团", "羟基", "羧基", "醛", "酮", "酯", "芳香", "取代",
                    "加成", "消去", "异构", "手性", "共轭"),
            "计算机科学", List.of("算法", "数据结构", "复杂度", "并发", "缓存", "数据库",
                    "索引", "事务", "容器", "微服务", "编译", "内存", "线程", "接口"),
            "数学", List.of("矩阵", "向量", "微分", "积分", "概率", "收敛", "特征值",
                    "极限", "函数", "线性", "分布", "方差", "定理")
    );

    private String stripMarkdown(String body) {
        return body.replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("`([^`]*)`", "$1")
                .replaceAll("!\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("^#{1,6}\\s*", "")
                .replaceAll("^\\s*>\\s?", "")
                .replaceAll("^\\s*[-*+]\\s+", "")
                .replaceAll("^\\s*\\d+[.)]\\s+", "")
                .replaceAll("[*_~|]", "");
    }

    /** 按中英文句号、问号、叹号、分号切句。 */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            boolean isTerminal = c == '。' || c == '！' || c == '？' || c == '；'
                    || (c == '.' && i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1)));
            if (isTerminal || c == '\n') {
                String sentence = current.toString().trim();
                if (sentence.length() >= 6) sentences.add(sentence.replaceAll("[。！？；]$", ""));
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (tail.length() >= 6) sentences.add(tail);
        return sentences;
    }

    /** 中文按 2-gram、英文数字按单词切分。 */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        List<Character> cjk = new ArrayList<>();

        for (char c : text.toCharArray()) {
            if (CJK.matcher(String.valueOf(c)).matches()) {
                flushLatin(latin, tokens);
                cjk.add(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, tokens);
                latin.append(c);
            } else {
                flushLatin(latin, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushLatin(latin, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private void flushLatin(StringBuilder latin, List<String> tokens) {
        if (latin.length() >= 2) tokens.add(latin.toString().toLowerCase(Locale.ROOT));
        latin.setLength(0);
    }

    private void flushCjk(List<Character> cjk, List<String> tokens) {
        for (int i = 0; i + 1 < cjk.size(); i++) {
            tokens.add("" + cjk.get(i) + cjk.get(i + 1));
        }
        cjk.clear();
    }

    private Map<String, Integer> termFrequency(String body) {
        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (String token : tokenize(stripMarkdown(body))) {
            frequency.merge(token, 1, Integer::sum);
        }
        return frequency;
    }

    private List<String> splitTags(String content) {
        if (content == null || content.isBlank()) return List.of();
        return Arrays.stream(content.split("[,，、;；\\n]"))
                .map(item -> item.replaceAll("^\\s*[-*•]\\s*", "").trim())
                .filter(item -> !item.isEmpty() && item.length() <= 24)
                .distinct().limit(8).toList();
    }

    private List<String> splitLines(String content) {
        if (content == null || content.isBlank()) return List.of();
        return Arrays.stream(content.split("\n"))
                .map(String::trim).filter(line -> !line.isEmpty())
                .limit(120).toList();
    }
}
