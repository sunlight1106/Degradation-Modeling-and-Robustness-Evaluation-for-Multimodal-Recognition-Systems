package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "provider_credential")
public class ProviderCredentialEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ModelProvider provider;
    @Column(nullable = false, length = 80)
    private String label;
    @Column(name = "encrypted_secret", nullable = false, columnDefinition = "TEXT")
    private String encryptedSecret;
    @Column(nullable = false, length = 20)
    private String fingerprint;
    @Column(nullable = false)
    private boolean active = true;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private UserEntity createdBy;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "disabled_at")
    private Instant disabledAt;

    protected ProviderCredentialEntity() {}
    public ProviderCredentialEntity(ModelProvider provider, String label, String encryptedSecret, String fingerprint, UserEntity createdBy) {
        this.provider = provider; this.label = label; this.encryptedSecret = encryptedSecret; this.fingerprint = fingerprint; this.createdBy = createdBy;
    }
    public Long getId() { return id; }
    public ModelProvider getProvider() { return provider; }
    public String getLabel() { return label; }
    public String getEncryptedSecret() { return encryptedSecret; }
    public String getFingerprint() { return fingerprint; }
    public boolean isActive() { return active; }
    public UserEntity getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDisabledAt() { return disabledAt; }
    public void disable() { active = false; disabledAt = Instant.now(); }
}
