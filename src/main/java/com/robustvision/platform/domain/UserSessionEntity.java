package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A revocable login, never a stored bearer token. */
@Entity
@Table(name = "user_session")
public class UserSessionEntity {
    @Id @Column(length = 36) private String id = UUID.randomUUID().toString();
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false) private UserEntity user;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @Column(name = "last_seen_at", nullable = false) private Instant lastSeenAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "user_agent", length = 400) private String userAgent;
    protected UserSessionEntity() {}
    public UserSessionEntity(UserEntity user, Instant createdAt, Instant expiresAt, String userAgent) {
        this.user = user; this.createdAt = createdAt; this.expiresAt = expiresAt;
        this.lastSeenAt = createdAt; this.userAgent = userAgent;
    }
    public String getId() { return id; }
    public UserEntity getUser() { return user; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getUserAgent() { return userAgent; }
}
