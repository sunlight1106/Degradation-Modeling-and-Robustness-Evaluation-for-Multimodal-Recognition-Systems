package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.FileAssetEntity;
import com.robustvision.platform.domain.InferenceStatus;
import com.robustvision.platform.domain.TaskType;
import com.robustvision.platform.dto.ApiDtos;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class TrainingDatasetService {
    private final InferenceService inferenceService;
    private final FileService fileService;
    private final ObjectMapper objectMapper;

    public TrainingDatasetService(InferenceService inferenceService, FileService fileService, ObjectMapper objectMapper) {
        this.inferenceService = inferenceService;
        this.fileService = fileService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public byte[] exportAccessible(TaskType taskType, String requestedVariant) {
        String variant = requestedVariant == null || requestedVariant.isBlank()
                ? "all" : requestedVariant.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("all", "baseline", "optimized").contains(variant)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TRAINING_VARIANT_INVALID",
                    "variant 仅支持 all、baseline 或 optimized");
        }
        List<ApiDtos.InferenceView> tasks = inferenceService.listAccessible().stream()
                .filter(task -> task.status() == InferenceStatus.COMPLETED)
                .filter(task -> taskType == null || task.taskType() == taskType)
                .toList();
        if (tasks.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "TRAINING_DATASET_EMPTY", "暂无已完成实验，无法导出训练数据集");
        }

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            StringBuilder manifest = new StringBuilder();
            Set<String> includedFiles = new HashSet<>();

            for (ApiDtos.InferenceView task : tasks) {
                if (!"optimized".equals(variant)) {
                    addSample(zip, manifest, includedFiles, task, task.inputFile(), task.baselineResult(),
                            task.baselineConfidence(), "baseline");
                }
                if (!"baseline".equals(variant) && task.outputFile() != null
                        && task.optimizedResult() != null && !task.optimizedResult().isNull()) {
                    addSample(zip, manifest, includedFiles, task, task.outputFile(), task.optimizedResult(),
                            task.optimizedConfidence(), "optimized");
                }
            }

            putEntry(zip, "manifest.jsonl", manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("schemaVersion", "1.0");
            metadata.put("exportedAt", Instant.now());
            metadata.put("taskCount", tasks.size());
            metadata.put("sampleCount", manifest.toString().lines().count());
            metadata.put("taskTypeFilter", taskType == null ? "ALL" : taskType.name());
            metadata.put("variantFilter", variant);
            metadata.put("labelStatus", "UNVERIFIED_TEACHER_LABEL");
            metadata.put("notice", "模型输出不是人工真值。训练或微调前必须人工复核，并划分独立验证集。当前供应商适配器未实现训练任务端点。");
            putEntry(zip, "dataset-card.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(metadata));
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "TRAINING_DATASET_EXPORT_FAILED", "训练数据集导出失败");
        }
    }

    private void addSample(ZipOutputStream zip, StringBuilder manifest, Set<String> includedFiles,
                           ApiDtos.InferenceView task, ApiDtos.FileView fileView, JsonNode result,
                           Double confidence, String variant) throws IOException {
        FileAssetEntity file = fileService.requireAccessible(fileView.id());
        String mediaPath = "media/" + file.getId() + extension(file.getContentType());
        if (includedFiles.add(file.getId())) {
            putEntry(zip, mediaPath, fileService.readBytes(file));
        }

        Map<String, Object> label = new LinkedHashMap<>();
        label.put("prediction", jsonValue(result.path("prediction")));
        label.put("quality", jsonValue(result.path("quality")));
        label.put("analysis", jsonValue(result.path("analysis")));
        label.put("warnings", jsonValue(result.path("warnings")));
        label.put("confidence", confidence);
        label.put("confidenceSource", result.path("confidenceSource").asText("UNKNOWN"));

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("sampleId", task.id() + ":" + variant);
        sample.put("split", split(fileView.sha256()));
        sample.put("taskType", task.taskType());
        sample.put("variant", variant);
        sample.put("media", mediaPath);
        sample.put("contentType", file.getContentType());
        sample.put("sha256", fileView.sha256());
        sample.put("traceId", task.traceId());
        sample.put("teacherModel", Map.of("code", task.model().code(), "version", task.model().version()));
        sample.put("reviewStatus", "UNVERIFIED_TEACHER_LABEL");
        sample.put("label", label);
        manifest.append(objectMapper.writeValueAsString(sample)).append('\n');
    }

    private Object jsonValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        return objectMapper.convertValue(node, Object.class);
    }

    private String split(String sha256) {
        int bucket = Integer.parseInt(sha256.substring(0, 2), 16) % 10;
        if (bucket < 7) return "train";
        if (bucket < 9) return "validation";
        return "test";
    }

    private String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            default -> ".jpg";
        };
    }

    private void putEntry(ZipOutputStream zip, String path, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content);
        zip.closeEntry();
    }
}
