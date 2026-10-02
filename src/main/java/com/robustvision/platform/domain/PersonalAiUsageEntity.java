package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Only operational metadata. Never store prompts, generated text, keys, or upstream error bodies. */
@Entity @Table(name = "personal_ai_usage")
public class PersonalAiUsageEntity {
    @Id @Column(length = 36) private String id = UUID.randomUUID().toString();
    @Column(name = "owner_id", nullable = false, updatable = false) private Long ownerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private AiProvider provider;
    @Column(nullable = false, length = 160) private String model;
    @Column(nullable = false, length = 20) private String action;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "input_tokens") private Long inputTokens;
    @Column(name = "output_tokens") private Long outputTokens;
    @Column(name = "error_code", length = 60) private String errorCode;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    protected PersonalAiUsageEntity() {}
    public PersonalAiUsageEntity(Long ownerId, AiProvider provider, String model, String action,
                                 String status, Long inputTokens, Long outputTokens, String errorCode) {
        this.ownerId = ownerId; this.provider = provider; this.model = model; this.action = action;
        this.status = status; this.inputTokens = inputTokens; this.outputTokens = outputTokens; this.errorCode = errorCode;
    }
    public PersonalAiUsageEntity(Long ownerId, AiProvider provider, String model, String action,
                                 String status, long inputTokens, long outputTokens, String errorCode) {
        this(ownerId,provider,model,action,status,Long.valueOf(inputTokens),Long.valueOf(outputTokens),errorCode);
    }
    public String getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public AiProvider getProvider() { return provider; }
    public String getModel() { return model; }
    public String getAction() { return action; }
    public String getStatus() { return status; }
    public Long getInputTokens() { return inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
    public String getErrorCode() { return errorCode; }
    public Instant getCreatedAt() { return createdAt; }
}
