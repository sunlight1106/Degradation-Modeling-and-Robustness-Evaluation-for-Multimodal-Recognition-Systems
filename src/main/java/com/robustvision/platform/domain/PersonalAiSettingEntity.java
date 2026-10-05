package com.robustvision.platform.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "personal_ai_setting", uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "provider"}))
public class PersonalAiSettingEntity {
    @Id @Column(length = 36) private String id = UUID.randomUUID().toString();
    @Column(name = "owner_id", nullable = false, updatable = false) private Long ownerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24, updatable = false) private AiProvider provider;
    @Column(nullable = false, length = 160) private String model;
    @Column(name = "base_url", nullable = false, length = 500) private String baseUrl;
    @JsonIgnore @Column(name = "encrypted_key", nullable = false, length = 6000) private String encryptedKey;
    @Column(nullable = false) private boolean enabled;
    @Version @Column(nullable = false) private long revision;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    protected PersonalAiSettingEntity() {}
    public PersonalAiSettingEntity(Long ownerId, AiProvider provider) { this.ownerId = ownerId; this.provider = provider; }
    public String getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public AiProvider getProvider() { return provider; }
    public String getModel() { return model; }
    public String getBaseUrl() { return baseUrl; }
    @JsonIgnore public String getEncryptedKey() { return encryptedKey; }
    public boolean isEnabled() { return enabled; }
    public long getRevision() { return revision; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void update(String model, String baseUrl, String encryptedKey, boolean enabled) {
        this.model = model; this.baseUrl = baseUrl; this.encryptedKey = encryptedKey;
        this.enabled = enabled; this.updatedAt = Instant.now();
    }
}
