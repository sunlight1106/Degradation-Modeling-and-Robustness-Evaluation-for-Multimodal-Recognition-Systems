package com.robustvision.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inference_task")
public class InferenceTaskEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "trace_id", nullable = false, unique = true, length = 36)
    private String traceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 30)
    private TaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InferenceStatus status = InferenceStatus.PENDING;

    @Column(name = "enhancement_enabled", nullable = false)
    private boolean enhancementEnabled;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "input_file_id", nullable = false)
    private FileAssetEntity inputFile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "output_file_id")
    private FileAssetEntity outputFile;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private ModelDefinitionEntity model;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private UserEntity requestedBy;

    @Column(name = "baseline_confidence")
    private Double baselineConfidence;

    @Column(name = "optimized_confidence")
    private Double optimizedConfidence;

    @Column(name = "baseline_latency_ms")
    private Long baselineLatencyMs;

    @Column(name = "optimized_latency_ms")
    private Long optimizedLatencyMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20)
    private ModelProvider provider;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "cost_cny", precision = 14, scale = 6)
    private java.math.BigDecimal costCny;

    @Lob
    @Column(name = "baseline_result", columnDefinition = "longtext")
    private String baselineResult;

    @Lob
    @Column(name = "optimized_result", columnDefinition = "longtext")
    private String optimizedResult;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected InferenceTaskEntity() {}

    public InferenceTaskEntity(String traceId, TaskType taskType, boolean enhancementEnabled,
                               FileAssetEntity inputFile, ModelDefinitionEntity model, UserEntity requestedBy) {
        this.traceId = traceId;
        this.taskType = taskType;
        this.enhancementEnabled = enhancementEnabled;
        this.inputFile = inputFile;
        this.model = model;
        this.requestedBy = requestedBy;
        this.provider = model.getProvider();
    }

    @PrePersist
    void ensureId() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public String getTraceId() { return traceId; }
    public TaskType getTaskType() { return taskType; }
    public InferenceStatus getStatus() { return status; }
    public boolean isEnhancementEnabled() { return enhancementEnabled; }
    public FileAssetEntity getInputFile() { return inputFile; }
    public FileAssetEntity getOutputFile() { return outputFile; }
    public ModelDefinitionEntity getModel() { return model; }
    public UserEntity getRequestedBy() { return requestedBy; }
    public Double getBaselineConfidence() { return baselineConfidence; }
    public Double getOptimizedConfidence() { return optimizedConfidence; }
    public Long getBaselineLatencyMs() { return baselineLatencyMs; }
    public Long getOptimizedLatencyMs() { return optimizedLatencyMs; }
    public ModelProvider getProvider() { return provider; }
    public Long getInputTokens() { return inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
    public java.math.BigDecimal getCostCny() { return costCny; }
    public String getBaselineResult() { return baselineResult; }
    public String getOptimizedResult() { return optimizedResult; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setStatus(InferenceStatus status) { this.status = status; }
    public void setOutputFile(FileAssetEntity outputFile) { this.outputFile = outputFile; }
    public void setBaselineConfidence(Double baselineConfidence) { this.baselineConfidence = baselineConfidence; }
    public void setOptimizedConfidence(Double optimizedConfidence) { this.optimizedConfidence = optimizedConfidence; }
    public void setBaselineLatencyMs(Long baselineLatencyMs) { this.baselineLatencyMs = baselineLatencyMs; }
    public void setOptimizedLatencyMs(Long optimizedLatencyMs) { this.optimizedLatencyMs = optimizedLatencyMs; }
    public void setUsage(long inputTokens, long outputTokens, java.math.BigDecimal costCny) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.costCny = costCny;
    }
    public void setBaselineResult(String baselineResult) { this.baselineResult = baselineResult; }
    public void setOptimizedResult(String optimizedResult) { this.optimizedResult = optimizedResult; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
