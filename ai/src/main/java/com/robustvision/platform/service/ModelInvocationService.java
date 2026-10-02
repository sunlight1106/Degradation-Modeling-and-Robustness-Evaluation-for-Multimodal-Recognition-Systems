package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

/** Legacy comparison runs are synthetic-only. Remote work must use explicit per-user BYOK consent. */
@Service
public class ModelInvocationService {
    private final String mode;
    public ModelInvocationService(@Value("${app.model.mode:demo}") String mode) { this.mode = mode; }

    public void assertLegacyAvailable() {
        if (!"demo".equalsIgnoreCase(mode)) throw new BusinessException(HttpStatus.CONFLICT,
                "PERSONAL_AI_REQUIRED", "共享密钥实验已停用，请使用个人 AI 图片识别并确认自己的供应商及输入；视频真实调用暂未开放");
    }
    public InvocationResult invoke(FileAssetEntity file, ModelDefinitionEntity model, TaskType taskType, boolean enhanced, String traceId) {
        assertLegacyAvailable();
        return invokeDemo(file, model, taskType, enhanced);
    }
    public RuntimeInfo runtimeInfo() {
        boolean demo = "demo".equalsIgnoreCase(mode);
        return new RuntimeInfo(mode, "PERSONAL_BYOK", "由用户个人配置选择", "/api/v1/personal-ai/recognition", false,
                Map.of("inference", demo, "vision", demo, "video", demo, "audioDenoise", demo,
                        "structuredOutput", demo, "datasetExport", true, "providerTraining", false, "providerFineTuning", false),
                List.of("旧实验入口仅运行明确标注的 DEMO 对照", "真实票据与车牌图片通过独立个人 BYOK 确认流程；不使用管理员密钥", "个人模型真实联调需用户自行配置，当前仅通过模拟契约验证", "真实视频调用暂未开放"), List.of());
    }
    private List<String> strategies(TaskType type) {
        return type == TaskType.VIDEO_ANALYSIS ? List.of("AUDIO_HIGH_LOW_PASS", "FFT_DENOISE", "LOUDNESS_NORMALIZATION")
                : List.of("LOCAL_CONTRAST_ENHANCEMENT");
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

    private double round(double value) { return Math.round(value * 10_000.0) / 10_000.0; }
    public record InvocationResult(double confidence, long latencyMs, Map<String, Object> payload,
                                   ModelProvider provider, long inputTokens, long outputTokens) {}
    public record ProviderRuntime(ModelProvider provider, String displayName, String model, String endpoint,
                                  boolean credentialConfigured, int configuredKeyCount, boolean videoSupported) {}
    public record RuntimeInfo(String mode, String provider, String model, String endpoint, boolean credentialConfigured,
                              Map<String, Boolean> capabilities, List<String> limitations, List<ProviderRuntime> providers) {}
}
