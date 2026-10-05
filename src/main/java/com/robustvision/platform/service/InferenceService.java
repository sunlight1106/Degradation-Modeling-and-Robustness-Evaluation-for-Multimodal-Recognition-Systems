package com.robustvision.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.FileAssetEntity;
import com.robustvision.platform.domain.InferenceStatus;
import com.robustvision.platform.domain.InferenceTaskEntity;
import com.robustvision.platform.domain.ModelDefinitionEntity;
import com.robustvision.platform.domain.TaskType;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.InferenceTaskRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InferenceService {
    private static final Logger log = LoggerFactory.getLogger(InferenceService.class);
    private final InferenceTaskRepository taskRepository;
    private final FileService fileService;
    private final ModelService modelService;
    private final ModelInvocationService invocationService;
    private final CurrentUserService currentUserService;
    private final InferenceQueueService queueService;
    private final BillingService billingService;
    private final ObjectMapper objectMapper;
    /**
     * 自身代理引用：类内部的 this 调用会绕过 Spring AOP，使被调用方法上的
     * {@code @Transactional} 失效，导致后续访问懒加载关联时抛
     * LazyInitializationException（open-in-view 已关闭）。通过代理调用可恢复事务边界。
     */
    private final InferenceService self;

    public InferenceService(InferenceTaskRepository taskRepository, FileService fileService,
                            ModelService modelService, ModelInvocationService invocationService,
                            CurrentUserService currentUserService, InferenceQueueService queueService,
                            BillingService billingService, ObjectMapper objectMapper,
                            @Lazy InferenceService self) {
        this.taskRepository = taskRepository;
        this.fileService = fileService;
        this.modelService = modelService;
        this.invocationService = invocationService;
        this.currentUserService = currentUserService;
        this.queueService = queueService;
        this.billingService = billingService;
        this.objectMapper = objectMapper;
        this.self = self;
    }

    public ApiDtos.InferenceView create(ApiDtos.CreateInferenceRequest request) {
        UserEntity current = currentUserService.requireCurrent();
        if(request.taskType() == TaskType.IMAGE_UNDERSTANDING) throw new BusinessException(HttpStatus.BAD_REQUEST,"PERSONAL_VISION_REQUIRED","图片理解请使用个人 AI 入口");
        invocationService.assertLegacyAvailable();
        FileAssetEntity input = fileService.requireAccessible(request.fileId());
        ModelDefinitionEntity model = modelService.requireActive(request.modelId());
        if (model.getTaskType() != request.taskType()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MODEL_TASK_MISMATCH", "模型不支持所选任务类型");
        }
        boolean video = input.getContentType().startsWith("video/");
        if (video != (request.taskType() == TaskType.VIDEO_ANALYSIS)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TASK_MEDIA_MISMATCH", video ? "视频文件只能选择视频分析任务" : "视频分析任务需要 MP4 或 WEBM 文件");
        }
        billingService.assertCanRun(current);
        InferenceTaskEntity task = new InferenceTaskEntity(UUID.randomUUID().toString(), request.taskType(),
                request.enhancementEnabled(), input, model, current);
        task = taskRepository.saveAndFlush(task);
        if (queueService.inline()) {
            // 与队列 Worker 使用相同的事务入口，确保关联实体可读取。
            self.processQueuedTask(task.getId());
        } else {
            try { queueService.enqueue(task.getId()); }
            catch (BusinessException exception) {
                // LPUSH may have succeeded before a timeout. Keep the saved task recoverable,
                // and never overwrite a result a worker may already have committed.
                throw new BusinessException(exception.getStatus(), exception.getCode(),
                        "队列提交未确认，任务已保存。请在 Logs 刷新状态或恢复等待中的任务，不要重复创建");
            }
        }
        // 必须经代理调用，否则 requireView 上的事务注解失效，
        // 序列化输入/输出文件等懒加载关联时会抛 LazyInitializationException
        return self.requireView(task.getId());
    }

    /** The failure transaction starts only after execution has committed or fully rolled back. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processQueuedTask(String taskId) {
        try {
            self.executePendingTask(taskId);
        } catch (Exception exception) {
            String message = exception instanceof BusinessException ? exception.getMessage() : "实验执行失败";
            log.error("Inference task failed taskId={} type={}", taskId, exception.getClass().getName());
            self.failPendingTask(taskId, message);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void executePendingTask(String taskId) throws JsonProcessingException {
        // Hold the row lock until billing and COMPLETED commit together. This path is
        // synthetic-only; never use this long transaction for paid/provider requests.
        InferenceTaskEntity task = taskRepository.findByIdForUpdate(taskId).orElse(null);
        if (task == null || task.getStatus() != InferenceStatus.PENDING) return;
        invocationService.assertLegacyAvailable();
        task.setStatus(InferenceStatus.RUNNING);
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        ModelInvocationService.InvocationResult baseline = invocationService.invoke(
                task.getInputFile(), task.getModel(), task.getTaskType(), false, task.getTraceId());
        task.setBaselineConfidence(baseline.confidence());
        task.setBaselineLatencyMs(baseline.latencyMs());
        task.setBaselineResult(objectMapper.writeValueAsString(baseline.payload()));
        totalInputTokens += baseline.inputTokens();
        totalOutputTokens += baseline.outputTokens();

        if (task.isEnhancementEnabled()) {
            FileAssetEntity output = fileService.createEnhancedCopy(task.getInputFile(), task.getRequestedBy());
            ModelInvocationService.InvocationResult optimized = invocationService.invoke(
                    output, task.getModel(), task.getTaskType(), true, task.getTraceId());
            task.setOutputFile(output);
            task.setOptimizedConfidence(optimized.confidence());
            task.setOptimizedLatencyMs(optimized.latencyMs());
            task.setOptimizedResult(objectMapper.writeValueAsString(optimized.payload()));
            totalInputTokens += optimized.inputTokens();
            totalOutputTokens += optimized.outputTokens();
        }
        BigDecimal cost = billingService.recordUsage(task.getRequestedBy(), task.getModel().getProvider(),
                totalInputTokens, totalOutputTokens, task.getId());
        task.setUsage(totalInputTokens, totalOutputTokens, cost);
        task.setStatus(InferenceStatus.COMPLETED);
        task.setCompletedAt(Instant.now());
        taskRepository.save(task);
    }

    @Transactional
    public void failPendingTask(String taskId, String message) {
        InferenceTaskEntity task = taskRepository.findByIdForUpdate(taskId).orElse(null);
        // A duplicate delivery may have won after rollback. Never replace its terminal result.
        if (task == null || task.getStatus() != InferenceStatus.PENDING) return;
        task.setStatus(InferenceStatus.FAILED);
        String safeMessage = message == null ? "实验执行失败" : message;
        task.setErrorMessage(safeMessage.substring(0, Math.min(safeMessage.length(), 1000)));
        task.setCompletedAt(Instant.now());
        taskRepository.save(task);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ApiDtos.InferenceView recoverPendingTask(String taskId) {
        self.enqueueOwnedPendingTask(taskId);
        if (queueService.inline()) self.processQueuedTask(taskId);
        return self.requireView(taskId);
    }

    @Transactional
    public void enqueueOwnedPendingTask(String taskId) {
        UserEntity current = currentUserService.requireCurrent();
        if (!currentUserService.hasPermission(current, "experiment:run")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "TASK_RECOVERY_DENIED", "没有恢复实验的权限");
        }
        InferenceTaskEntity task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "实验不存在"));
        if (!task.getRequestedBy().getId().equals(current.getId())
                || !task.getInputFile().getOwner().getId().equals(current.getId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "TASK_RECOVERY_DENIED", "只能恢复本人使用自己文件创建的实验");
        }
        invocationService.assertLegacyAvailable();
        if (task.getStatus() != InferenceStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT, "TASK_NOT_PENDING", "仅等待中的 DEMO 实验可以重新入队，请刷新状态");
        }
        // Preserve PENDING on ambiguous Redis failure. Retrying adds only the same ID;
        // execution's database lock makes duplicate successful deliveries harmless.
        if (!queueService.inline()) queueService.enqueue(taskId);
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.InferenceView> listAccessible() {
        UserEntity current = currentUserService.requireCurrent();
        List<InferenceTaskEntity> tasks = currentUserService.hasPermission(current, "experiment:read:any")
                ? taskRepository.findAllByOrderByCreatedAtDesc()
                : taskRepository.findByRequestedByIdOrderByCreatedAtDesc(current.getId());
        return tasks.stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ApiDtos.InferenceView requireView(String id) { return toView(requireAccessible(id)); }

    @Transactional(readOnly = true)
    public byte[] report(String id) {
        InferenceTaskEntity task = requireAccessible(id);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "2.0");
        report.put("generatedAt", Instant.now());
        report.put("experiment", toView(task));
        report.put("notice", "模型自评估置信度必须使用人工标注集校准；视频降噪不保证每个样本准确率提高");
        try { return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report); }
        catch (JsonProcessingException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT_FAILED", "报告生成失败");
        }
    }

    public ApiDtos.InferenceView toView(InferenceTaskEntity task) {
        return new ApiDtos.InferenceView(
                task.getId(), task.getTraceId(), task.getTaskType(), task.getStatus(), task.isEnhancementEnabled(),
                fileService.toView(task.getInputFile()), task.getOutputFile() == null ? null : fileService.toView(task.getOutputFile()),
                modelService.toView(task.getModel()), task.getRequestedBy().getDisplayName(), task.getRequestedBy().getId(),
                task.getBaselineConfidence(), task.getOptimizedConfidence(), task.getBaselineLatencyMs(), task.getOptimizedLatencyMs(),
                task.getProvider(), task.getInputTokens(), task.getOutputTokens(), task.getCostCny(),
                parse(task.getBaselineResult()), parse(task.getOptimizedResult()), task.getErrorMessage(), task.getCreatedAt(),
                task.getCompletedAt(), "/api/v1/inference/tasks/" + task.getId() + "/report");
    }

    private InferenceTaskEntity requireAccessible(String id) {
        InferenceTaskEntity task = taskRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "实验不存在"));
        UserEntity current = currentUserService.requireCurrent();
        boolean owner = task.getRequestedBy().getId().equals(current.getId());
        if (!owner && !currentUserService.hasPermission(current, "experiment:read:any")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "TASK_ACCESS_DENIED", "无权查看此实验");
        }
        return task;
    }

    private JsonNode parse(String value) {
        if (value == null || value.isBlank()) return NullNode.getInstance();
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException exception) { return objectMapper.createObjectNode().put("raw", value); }
    }
}
