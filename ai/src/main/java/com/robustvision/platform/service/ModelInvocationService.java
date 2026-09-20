package com.robustvision.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.FileAssetEntity;
import com.robustvision.platform.domain.ModelDefinitionEntity;
import com.robustvision.platform.domain.ModelProvider;
import com.robustvision.platform.domain.TaskType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ModelInvocationService {
    private final FileService fileService;
    private final ObjectMapper objectMapper;
    private final ProviderKeyRingService keyRing;
    private final String mode;
    private final String httpBaseUrl;
    private final Map<ModelProvider, ProviderConfig> providers = new EnumMap<>(ModelProvider.class);

    public ModelInvocationService(FileService fileService, ObjectMapper objectMapper, ProviderKeyRingService keyRing,
                                  @Value("${app.model.mode:demo}") String mode,
                                  @Value("${app.model.base-url:http://localhost:8000}") String httpBaseUrl,
                                  @Value("${app.model.deepseek.base-url:https://api.deepseek.com}") String deepSeekUrl,
                                  @Value("${app.model.deepseek.model:deepseek-v4-flash-vision-exp}") String deepSeekModel,
                                  @Value("${app.model.kimi.base-url:https://api.moonshot.cn/v1}") String kimiUrl,
                                  @Value("${app.model.kimi.model:kimi-k3}") String kimiModel,
                                  @Value("${app.model.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String qwenUrl,
                                  @Value("${app.model.qwen.model:qwen3-vl-plus}") String qwenModel,
                                  @Value("${app.model.qwen.video-model:qwen3.5-omni-plus}") String qwenVideoModel) {
        this.fileService = fileService;
        this.objectMapper = objectMapper;
        this.keyRing = keyRing;
        this.mode = mode;
        this.httpBaseUrl = httpBaseUrl;
        providers.put(ModelProvider.DEEPSEEK, new ProviderConfig(deepSeekUrl, deepSeekModel, deepSeekModel));
        providers.put(ModelProvider.KIMI, new ProviderConfig(kimiUrl, kimiModel, kimiModel));
        providers.put(ModelProvider.QWEN, new ProviderConfig(qwenUrl, qwenModel, qwenVideoModel));
    }

    public InvocationResult invoke(FileAssetEntity file, ModelDefinitionEntity model,
                                   TaskType taskType, boolean enhanced, String traceId) {
        if ("demo".equalsIgnoreCase(mode)) return invokeDemo(file, model, taskType, enhanced);
        if ("http".equalsIgnoreCase(mode) || model.getProvider() == ModelProvider.CUSTOM) {
            return invokeHttp(file, model, taskType, enhanced, traceId);
        }
        if (taskType == TaskType.VIDEO_ANALYSIS && model.getProvider() == ModelProvider.DEEPSEEK) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MODEL_MEDIA_UNSUPPORTED", "当前 DeepSeek Vision 模型不接收视频，请选择 Kimi 或通义千问");
        }
        return invokeCompatible(file, model.getProvider(), taskType, enhanced, traceId);
    }

    public RuntimeInfo runtimeInfo() {
        List<ProviderRuntime> providerViews = List.of(ModelProvider.DEEPSEEK, ModelProvider.KIMI, ModelProvider.QWEN).stream()
                .map(provider -> {
                    ProviderConfig config = providers.get(provider);
                    return new ProviderRuntime(provider, keyRing.displayName(provider), config.modelLabel(), config.baseUrl(),
                            keyRing.configured(provider), keyRing.count(provider), provider != ModelProvider.DEEPSEEK);
                }).toList();
        return new RuntimeInfo(mode, "Multi-provider", "DeepSeek · Kimi · Qwen", "server-side adapters",
                providerViews.stream().anyMatch(ProviderRuntime::credentialConfigured),
                Map.of("inference", true, "vision", true, "video", true, "audioDenoise", true,
                        "structuredOutput", true, "datasetExport", true, "providerTraining", false, "providerFineTuning", false),
                List.of("模型置信度为供应商模型自评估值，必须用人工标注集校准", "视频音轨降噪只改善输入条件，不保证每个样本准确率提高"), providerViews);
    }

    @SuppressWarnings("unchecked")
    private InvocationResult invokeHttp(FileAssetEntity file, ModelDefinitionEntity model,
                                        TaskType taskType, boolean enhanced, String traceId) {
        byte[] bytes = fileService.readBytes(file);
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return file.getOriginalName(); }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);
        body.add("taskType", taskType.name());
        body.add("modelCode", model.getCode());
        body.add("modelVersion", model.getVersion());
        body.add("enhancementEnabled", enhanced);
        body.add("traceId", traceId);
        long started = System.nanoTime();
        try {
            Map<String, Object> response = RestClient.create(httpBaseUrl).post().uri("/v1/infer")
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(body).retrieve().body(Map.class);
            if (response == null) throw new IllegalStateException("empty model response");
            Number confidence = (Number) response.get("confidence");
            Number latency = (Number) response.get("latencyMs");
            Number inputTokens = (Number) response.get("inputTokens");
            Number outputTokens = (Number) response.get("outputTokens");
            long measured = elapsedMillis(started);
            return new InvocationResult(confidence == null ? 0 : confidence.doubleValue(),
                    latency == null ? measured : latency.longValue(), response, ModelProvider.CUSTOM,
                    inputTokens == null ? 0 : inputTokens.longValue(), outputTokens == null ? 0 : outputTokens.longValue());
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "MODEL_SERVICE_FAILED", "自建模型服务调用失败");
        }
    }

    private InvocationResult invokeCompatible(FileAssetEntity file, ModelProvider provider, TaskType taskType,
                                              boolean enhanced, String traceId) {
        ProviderConfig config = providers.get(provider);
        if (config == null) throw new BusinessException(HttpStatus.BAD_REQUEST, "MODEL_PROVIDER_UNSUPPORTED", "模型供应商尚未接入");
        byte[] bytes = fileService.readBytes(file);
        if (provider == ModelProvider.QWEN && taskType == TaskType.VIDEO_ANALYSIS && bytes.length > 7_000_000) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "QWEN_VIDEO_INLINE_LIMIT",
                    "千问音视频的 Base64 请求需小于 10 MB，请使用不超过约 7 MB 的短视频，或改用 Kimi 视频模型");
        }
        String mediaType = file.getContentType().startsWith("video/") ? "video_url" : "image_url";
        String dataUrl = "data:" + file.getContentType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
        long started = System.nanoTime();
        String providerModel = config.model(taskType);

        int attempts = Math.max(1, Math.min(5, keyRing.count(provider)));
        RestClientResponseException lastProviderError = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            String apiKey = keyRing.next(provider);
            String temporaryFileId = null;
            try {
                String mediaUrl = dataUrl;
                if (provider == ModelProvider.KIMI && taskType == TaskType.VIDEO_ANALYSIS) {
                    temporaryFileId = uploadKimiVideo(config.baseUrl(), apiKey, file, bytes);
                    mediaUrl = "ms://" + temporaryFileId;
                }
                Map<String, Object> mediaPart = Map.of("type", mediaType, mediaType, Map.of("url", mediaUrl));
                Map<String, Object> textPart = Map.of("type", "text", "text", userPrompt(taskType, enhanced, traceId));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", providerModel);
                body.put("messages", List.of(
                        Map.of("role", "system", "content", systemPrompt(taskType)),
                        Map.of("role", "user", "content", List.of(textPart, mediaPart))));
                if (provider != ModelProvider.KIMI) body.put("temperature", 0.1);
                body.put("max_tokens", 1600);
                if (provider == ModelProvider.DEEPSEEK) body.put("thinking", Map.of("type", "disabled"));
                if (!(provider == ModelProvider.QWEN && taskType == TaskType.VIDEO_ANALYSIS)) {
                    body.put("response_format", Map.of("type", "json_object"));
                }
                JsonNode response = RestClient.create(config.baseUrl()).post().uri("/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
                if (response == null) throw new IllegalStateException("empty provider response");
                String content = extractMessageContent(response);
                JsonNode generated = parseGeneratedJson(content);
                double confidence = clamp(generated.path("confidence").asDouble(0));
                long latency = elapsedMillis(started);
                long inputTokens = response.path("usage").path("prompt_tokens").asLong(0);
                long outputTokens = response.path("usage").path("completion_tokens").asLong(0);

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", "SUCCESS");
                payload.put("adapter", provider.name() + "_API");
                payload.put("provider", keyRing.displayName(provider));
                payload.put("providerRequestId", response.path("id").asText());
                payload.put("model", providerModel);
                payload.put("confidence", confidence);
                payload.put("confidenceSource", "MODEL_SELF_ASSESSMENT_NOT_CALIBRATED");
                payload.put("latencyMs", latency);
                payload.put("prediction", jsonValue(generated.path("prediction")));
                payload.put("quality", jsonValue(generated.path("quality")));
                payload.put("analysis", jsonValue(generated.path("analysis")));
                payload.put("warnings", jsonValue(generated.path("warnings")));
                payload.put("route", Map.of("version", "personal-route-2.0", "strategies",
                        enhanced ? strategies(taskType) : List.of("BASELINE")));
                payload.put("usage", jsonValue(response.path("usage")));
                return new InvocationResult(confidence, latency, payload, provider, inputTokens, outputTokens);
            } catch (RestClientResponseException exception) {
                lastProviderError = exception;
                int status = exception.getStatusCode().value();
                if ((status == 401 || status == 429) && attempt + 1 < attempts) continue;
                throw providerException(provider, status);
            } catch (BusinessException exception) {
                throw exception;
            } catch (JsonProcessingException exception) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "MODEL_RESPONSE_INVALID", keyRing.displayName(provider) + " 返回的结构化结果无法解析");
            } catch (Exception exception) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "MODEL_PROVIDER_ERROR", keyRing.displayName(provider) + " 暂时无法完成推理");
            } finally {
                if (temporaryFileId != null) deleteKimiFile(config.baseUrl(), apiKey, temporaryFileId);
            }
        }
        throw providerException(provider, lastProviderError == null ? 502 : lastProviderError.getStatusCode().value());
    }

    private String uploadKimiVideo(String baseUrl, String apiKey, FileAssetEntity file, byte[] bytes) {
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return file.getOriginalName(); }
        };
        MultiValueMap<String, Object> upload = new LinkedMultiValueMap<>();
        upload.add("file", resource);
        upload.add("purpose", "video");
        JsonNode response = RestClient.create(baseUrl).post().uri("/files")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(upload).retrieve().body(JsonNode.class);
        String id = response == null ? "" : response.path("id").asText();
        if (id.isBlank()) throw new BusinessException(HttpStatus.BAD_GATEWAY, "KIMI_FILE_UPLOAD_FAILED", "Kimi 视频文件上传失败");
        return id;
    }

    private void deleteKimiFile(String baseUrl, String apiKey, String fileId) {
        try {
            RestClient.create(baseUrl).delete().uri("/files/{id}", fileId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).retrieve().toBodilessEntity();
        } catch (Exception ignored) { }
    }

    private BusinessException providerException(ModelProvider provider, int status) {
        if (status == 401) return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_API_KEY_REJECTED", keyRing.displayName(provider) + " 拒绝了密钥，密钥环已轮换");
        if (status == 429) return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "MODEL_PROVIDER_RATE_LIMIT", keyRing.displayName(provider) + " 触发供应商限流，密钥环已轮换");
        if (status == 402) return new BusinessException(HttpStatus.PAYMENT_REQUIRED, "MODEL_PROVIDER_BALANCE_LOW", keyRing.displayName(provider) + " 账户余额不足");
        return new BusinessException(HttpStatus.BAD_GATEWAY, "MODEL_PROVIDER_ERROR", keyRing.displayName(provider) + " 暂时无法完成推理");
    }

    private String systemPrompt(TaskType taskType) {
        String task = switch (taskType) {
            case LICENSE_PLATE -> "车牌识别：读取车牌文本并拆分字段；无法确认的字符使用 ?，不得猜测。";
            case RECEIPT -> "票据识别：读取商户、日期、总金额、币种和可见明细；看不清返回 null，不得编造。";
            case VIDEO_ANALYSIS -> "视频理解：总结场景、事件、可见文字、时间线和音频线索；无法确认的内容必须标为不确定。";
        };
        return """
                你是 Personal Platform 的多模态可靠性分析器。%s
                只返回合法 JSON 对象，不要 Markdown。必须包含：
                prediction: {text: string, fields: object}; confidence: 0 到 1；
                quality: {score: 0 到 1, bucket: GOOD|MEDIUM|POOR, labels: string[], degradation: object};
                analysis: {summary: string, evidence: string[], uncertainties: string[]}; warnings: string[]。
                质量分必须依据输入。置信度不是统计校准概率。不要输出思维过程。
                """.formatted(task);
    }

    private String userPrompt(TaskType taskType, boolean enhanced, String traceId) {
        String media = taskType == TaskType.VIDEO_ANALYSIS ? "视频" : taskType == TaskType.LICENSE_PLATE ? "车牌场景图" : "票据图";
        String treatment = enhanced
                ? taskType == TaskType.VIDEO_ANALYSIS ? "该版本的音轨经过频谱降噪与响度归一化，请独立判断，不要因优化标签人为提高置信度。"
                : "该图片经过固定本地对比度处理，请独立判断，不要因优化标签人为提高置信度。"
                : "这是未经优化的基线输入。";
        return "请分析这份" + media + "。" + treatment + " 使用简体中文返回证据、不确定项与警告。trace_id=" + traceId;
    }

    private List<String> strategies(TaskType type) {
        return type == TaskType.VIDEO_ANALYSIS
                ? List.of("AUDIO_HIGH_LOW_PASS", "FFT_DENOISE", "LOUDNESS_NORMALIZATION")
                : List.of("LOCAL_CONTRAST_ENHANCEMENT");
    }

    private JsonNode parseGeneratedJson(String content) throws JsonProcessingException {
        String normalized = content == null ? "" : content.trim();
        if (normalized.startsWith("```")) {
            int firstNewline = normalized.indexOf('\n');
            int lastFence = normalized.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) normalized = normalized.substring(firstNewline + 1, lastFence).trim();
        }
        JsonNode result = objectMapper.readTree(normalized);
        if (!result.isObject()) throw new JsonProcessingException("model result is not an object") {};
        return result;
    }

    private String extractMessageContent(JsonNode response) {
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                String value = part.isTextual() ? part.asText() : part.path("text").asText();
                if (!value.isBlank()) text.append(value);
            }
            return text.toString();
        }
        return content.asText();
    }

    private Object jsonValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        return objectMapper.convertValue(node, Object.class);
    }

    private InvocationResult invokeDemo(FileAssetEntity file, ModelDefinitionEntity model, TaskType taskType, boolean enhanced) {
        long seed = Long.parseUnsignedLong(file.getSha256().substring(0, 8), 16);
        double baseline = 0.68 + (seed % 1400) / 10_000.0;
        double confidence = enhanced ? Math.min(0.97, baseline + 0.06 + (seed % 400) / 10_000.0) : baseline;
        long latency = 48 + (seed % 60) + (enhanced ? 36 : 0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "SUCCESS"); payload.put("adapter", "DEMO");
        payload.put("notice", "演示适配器输出，不代表真实模型精度");
        payload.put("confidence", round(confidence)); payload.put("confidenceSource", "DETERMINISTIC_DEMO");
        payload.put("latencyMs", latency);
        payload.put("prediction", switch (taskType) {
            case LICENSE_PLATE -> platePrediction(seed);
            case RECEIPT -> receiptPrediction(seed);
            case VIDEO_ANALYSIS -> Map.of("text", "演示视频：道路场景与车辆经过", "fields", Map.of("events", List.of("vehicle_pass"), "duration", "demo"));
        });
        payload.put("quality", Map.of("score", round(0.58 + (seed % 2600) / 10_000.0), "bucket", enhanced ? "GOOD" : "MEDIUM",
                "labels", taskType == TaskType.VIDEO_ANALYSIS ? List.of("BACKGROUND_NOISE", "LOW_VOLUME") : List.of("LOW_CONTRAST", "COMPRESSION"),
                "degradation", taskType == TaskType.VIDEO_ANALYSIS ? Map.of("backgroundNoise", 0.62, "lowVolume", 0.34) : Map.of("lowLight", 0.62, "blur", 0.34, "glare", 0.18)));
        payload.put("analysis", Map.of("summary", "确定性演示分析，仅用于验证页面和数据链路。", "evidence", List.of("输入哈希已固定"), "uncertainties", List.of("未运行真实模型")));
        payload.put("warnings", List.of("DEMO 结果不得用于论文结论"));
        payload.put("route", Map.of("version", "demo-route-0.2", "strategies", enhanced ? strategies(taskType) : List.of("BASELINE")));
        payload.put("model", Map.of("code", model.getCode(), "version", model.getVersion()));
        return new InvocationResult(round(confidence), latency, payload, ModelProvider.DEMO, 0, 0);
    }

    private Map<String, Object> platePrediction(long seed) {
        String plate = "苏C" + String.format(Locale.ROOT, "%05d", seed % 100_000);
        return Map.of("text", plate, "fields", Map.of("plateNumber", plate));
    }

    private Map<String, Object> receiptPrediction(long seed) {
        double amount = 20 + (seed % 30_000) / 100.0;
        LocalDate demoDate = LocalDate.of(2025, 1, 1).plusDays(seed % 730);
        return Map.of("text", "演示票据文本", "fields", Map.of("merchant", "示例商户", "date", demoDate.toString(),
                "amount", String.format(Locale.ROOT, "%.2f", amount), "currency", "CNY"));
    }

    private long elapsedMillis(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private double round(double value) { return Math.round(value * 10_000.0) / 10_000.0; }

    private record ProviderConfig(String baseUrl, String imageModel, String videoModel) {
        String model(TaskType taskType) { return taskType == TaskType.VIDEO_ANALYSIS ? videoModel : imageModel; }
        String modelLabel() { return imageModel.equals(videoModel) ? imageModel : imageModel + " / " + videoModel; }
    }
    public record InvocationResult(double confidence, long latencyMs, Map<String, Object> payload,
                                   ModelProvider provider, long inputTokens, long outputTokens) {}
    public record ProviderRuntime(ModelProvider provider, String displayName, String model, String endpoint,
                                  boolean credentialConfigured, int configuredKeyCount, boolean videoSupported) {}
    public record RuntimeInfo(String mode, String provider, String model, String endpoint, boolean credentialConfigured,
                              Map<String, Boolean> capabilities, List<String> limitations, List<ProviderRuntime> providers) {}
}
