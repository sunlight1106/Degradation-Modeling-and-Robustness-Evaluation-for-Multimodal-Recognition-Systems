package com.robustvision.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "model_definition", uniqueConstraints = @UniqueConstraint(name = "uk_model_code_version", columnNames = {"code", "version"}))
public class ModelDefinitionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 80)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ModelProvider provider = ModelProvider.DEEPSEEK;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 30)
    private TaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ModelStatus status = ModelStatus.ACTIVE;

    @Column(length = 500)
    private String endpoint;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ModelDefinitionEntity() {}

    public ModelDefinitionEntity(String code, String name, String version, ModelProvider provider,
                                 TaskType taskType, String description) {
        this.code = code;
        this.name = name;
        this.version = version;
        this.provider = provider;
        this.taskType = taskType;
        this.description = description;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public ModelProvider getProvider() { return provider; }
    public TaskType getTaskType() { return taskType; }
    public ModelStatus getStatus() { return status; }
    public String getEndpoint() { return endpoint; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public void setStatus(ModelStatus status) { this.status = status; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
}
