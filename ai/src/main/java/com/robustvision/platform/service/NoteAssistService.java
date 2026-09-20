package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.ModelProvider;
import com.robustvision.platform.dto.ApiDtos;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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

/**
 * 笔记 AI 整理服务：摘要、大纲、标签、格式整理四种动作。
 *
 * 引擎选择如实标注，绝不混淆：
 * - MODEL：live 模式且至少一家供应商配了密钥，走真实 chat completion
 * - LOCAL_RULES：demo 模式或无密钥，使用本地确定性算法
 *
 * 本地实现延续项目既有原则——不把规则结果冒充模型结论。响应中的 engine 字段
 * 与 note 字段会明确告知用户当前结果来自哪里，前端也会展示。
 */
@Service
public class NoteAssistService {

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final int MAX_BODY_CHARS = 24000;

    private final ProviderKeyRingService keyRing;
    private final ObjectMapper objectMapper;
    private final String mode;
    private final String qwenBaseUrl;
    private final String qwenModel;
    private final String deepSeekBaseUrl;
    private final String deepSeekModel;
    private final String kimiBaseUrl;
    private final String kimiModel;

    public NoteAssistService(ProviderKeyRingService keyRing,
                             ObjectMapper objectMapper,
                             @Value("${app.model.mode:demo}") String mode,
                             @Value("${app.model.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String qwenBaseUrl,
                             @Value("${app.model.qwen.model:qwen3-vl-plus}") String qwenModel,
                             @Value("${app.model.deepseek.base-url:https://api.deepseek.com}") String deepSeekBaseUrl,
                             @Value("${app.model.deepseek.model:deepseek-v4-flash-vision-exp}") String deepSeekModel,
                             @Value("${app.model.kimi.base-url:https://api.moonshot.cn/v1}") String kimiBaseUrl,
                             @Value("${app.model.kimi.model:kimi-k3}") String kimiModel) {
        this.keyRing = keyRing;
        this.objectMapper = objectMapper;
        this.mode = mode;
        this.qwenBaseUrl = qwenBaseUrl;
        this.qwenModel = qwenModel;
        this.deepSeekBaseUrl = deepSeekBaseUrl;
        this.deepSeekModel = deepSeekModel;
        this.kimiBaseUrl = kimiBaseUrl;
        this.kimiModel = kimiModel;
    }

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
        if (body.length() > MAX_BODY_CHARS) body = body.substring(0, MAX_BODY_CHARS);

        ModelProvider provider = selectProvider();
        if (provider != null) {
            try {
                return callModel(provider, action, request.title(), body, traceId);
            } catch (BusinessException exception) {
                // 模型不可用时降级到本地规则，但必须在结果中如实说明
                return local(action, body, traceId,
                        "模型调用失败（" + exception.getMessage() + "），已改用本地规则整理");
            }
        }
        return local(action, body, traceId, null);
    }

    // ------------------------------------------------------------------
    // 引擎选择与模型调用
    // ------------------------------------------------------------------

    /** 选择第一个配置了密钥的供应商；demo 模式或全未配置时返回 null。 */
    private ModelProvider selectProvider() {
        if (!"live".equalsIgnoreCase(mode)) return null;
        for (ModelProvider provider : List.of(ModelProvider.QWEN, ModelProvider.DEEPSEEK, ModelProvider.KIMI)) {
            if (keyRing.configured(provider)) return provider;
        }
        return null;
    }

    private ApiDtos.NoteAssistResponse callModel(ModelProvider provider, String action,
                                                 String title, String body, String traceId) {
        String baseUrl = switch (provider) {
            case QWEN -> qwenBaseUrl;
            case DEEPSEEK -> deepSeekBaseUrl;
            default -> kimiBaseUrl;
        };
        String model = switch (provider) {
            case QWEN -> qwenModel;
            case DEEPSEEK -> deepSeekModel;
            default -> kimiModel;
        };

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt()),
                Map.of("role", "user", "content", userPrompt(action, title, body))));
        payload.put("temperature", "tags".equals(action) || "outline".equals(action) ? 0.2 : 0.4);
        payload.put("max_tokens", 1600);

        int attempts = Math.max(1, Math.min(3, keyRing.count(provider)));
        BusinessException lastError = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            String apiKey = keyRing.next(provider);
            try {
                JsonNode response = RestClient.create(baseUrl).post()
                        .uri("/chat/completions")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .body(JsonNode.class);
                String content = response == null ? null
                        : response.path("choices").path(0).path("message").path("content").asText(null);
                if (content == null || content.isBlank()) {
                    throw new BusinessException(HttpStatus.BAD_GATEWAY, "ASSIST_MODEL_EMPTY",
                            keyRing.displayName(provider) + " 返回了空内容");
                }
                long inputTokens = response.path("usage").path("prompt_tokens").asLong(0);
                long outputTokens = response.path("usage").path("completion_tokens").asLong(0);
                return buildResponse(action, content, "MODEL:" + keyRing.displayName(provider),
                        traceId, inputTokens, outputTokens, null);
            } catch (BusinessException exception) {
                lastError = exception;
            } catch (Exception exception) {
                lastError = new BusinessException(HttpStatus.BAD_GATEWAY, "ASSIST_MODEL_ERROR",
                        keyRing.displayName(provider) + " 暂时无法完成整理");
            }
        }
        throw lastError != null ? lastError
                : new BusinessException(HttpStatus.BAD_GATEWAY, "ASSIST_MODEL_ERROR", "模型调用失败");
    }

    private String systemPrompt() {
        return """
                你是个人知识库的整理助手，服务于一个跨学科的笔记平台，涵盖生物化学、医学、计算机、数学等领域。
                你的任务是根据用户指定的动作整理笔记内容，要求：
                1. 严格忠于原文，不编造原文中没有的事实、数据或结论。
                2. 保留专业术语的准确表述，不要为了通顺而改变技术含义。
                3. 如果原文信息不足以完成某个动作，明确说明不足，而不是猜测补全。
                4. 使用简体中文输出，Markdown 格式。
                """;
    }

    private String userPrompt(String action, String title, String body) {
        String header = title == null || title.isBlank() ? "" : "笔记标题：" + title.trim() + "\n\n";
        String instruction = switch (action) {
            case "summarize" -> """
                    请为下面的笔记生成摘要，要求：
                    - 3 到 5 句话，150 字以内
                    - 覆盖核心结论与关键概念，不要罗列细节
                    - 直接输出摘要正文，不要加"摘要："之类的标签
                    """;
            case "outline" -> """
                    请为下面的笔记生成层级大纲，要求：
                    - 使用 Markdown 无序列表，用缩进表示层级
                    - 最多三级，每级条目不超过 12 个字
                    - 只输出大纲列表本身
                    """;
            case "tags" -> """
                    请为下面的笔记提取 3 到 6 个标签，要求：
                    - 每个标签 2 到 8 个字，覆盖学科领域与核心概念
                    - 逗号分隔，单行输出，不要编号，不要任何解释
                    """;
            case "tidy" -> """
                    请整理下面的笔记，要求：
                    - 修正 Markdown 语法错误、统一标点、压缩多余空行
                    - 可以调整语序让表达更清晰，但不得增删事实性内容
                    - 直接输出整理后的完整 Markdown 正文
                    """;
            default -> "请整理下面的笔记内容。";
        };
        return header + instruction + "\n笔记内容：\n\n" + body;
    }

    private ApiDtos.NoteAssistResponse buildResponse(String action, String content, String engine,
                                                     String traceId, long inputTokens, long outputTokens,
                                                     String note) {
        List<String> items = switch (action) {
            case "tags" -> splitTags(content);
            case "outline" -> splitLines(content);
            default -> List.of();
        };
        String suffix = inputTokens > 0 || outputTokens > 0
                ? "（本次消耗 " + inputTokens + " 输入 / " + outputTokens + " 输出 tokens）"
                : "";
        String finalNote = (note == null ? "" : note + " ")
                + "结果由 " + engine.replace("MODEL:", "") + " 生成" + suffix;
        return new ApiDtos.NoteAssistResponse(action, engine, content.trim(), items, finalNote.trim(), traceId);
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
