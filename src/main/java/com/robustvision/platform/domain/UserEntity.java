package com.robustvision.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "app_user")
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "identity_code", nullable = false, unique = true, updatable = false, length = 36)
    private String identityCode = "PKB-" + java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);

    @Column(nullable = false, unique = true, length = 60)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(nullable = false, unique = true, length = 160)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private RoleEntity role;

    @jakarta.persistence.ElementCollection(fetch = FetchType.EAGER)
    @jakarta.persistence.CollectionTable(name = "user_permission_override", joinColumns = @JoinColumn(name = "user_id"))
    @jakarta.persistence.MapKeyColumn(name = "permission_code", length = 80)
    @Column(name = "allowed", nullable = false)
    private java.util.Map<String, Boolean> permissionOverrides = new java.util.LinkedHashMap<>();

    @Column(name = "access_expires_at")
    private Instant accessExpiresAt;

    public java.util.Map<String, Boolean> getPermissionOverrides() { return permissionOverrides; }
    public void setPermissionOverrides(java.util.Map<String, Boolean> overrides) { this.permissionOverrides = new java.util.LinkedHashMap<>(overrides); }
    public Instant getAccessExpiresAt() { return accessExpiresAt; }
    public void setAccessExpiresAt(Instant expiry) { this.accessExpiresAt = expiry; }
    public boolean hasActiveAccess(Instant now) { return status == UserStatus.ACTIVE && (accessExpiresAt == null || accessExpiresAt.isAfter(now)); }

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(nullable = false) private boolean discoverable = true;
    public boolean isDiscoverable() { return discoverable; }
    public void setDiscoverable(boolean value) { discoverable = value; }

    protected UserEntity() {}

    public UserEntity(String username, String passwordHash, String displayName, String email, RoleEntity role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.email = email;
        this.role = role;
    }

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getIdentityCode() { return identityCode; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public UserStatus getStatus() { return status; }
    public RoleEntity getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setEmail(String email) { this.email = email; }
    public void setStatus(UserStatus status) { this.status = status; }
    public void setRole(RoleEntity role) { this.role = role; }
}

