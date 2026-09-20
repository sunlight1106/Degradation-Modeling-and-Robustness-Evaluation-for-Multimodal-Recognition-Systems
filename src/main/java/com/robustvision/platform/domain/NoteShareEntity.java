package com.robustvision.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 笔记分享令牌。分享仅对已登录用户开放，token 为 32 位随机串，
 * 可设置过期时间，revoked_at 非空表示已撤销。
 */
@Entity
@Table(name = "note_share")
public class NoteShareEntity {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "note_id", nullable = false)
    private NoteEntity note;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shared_by", nullable = false)
    private UserEntity sharedBy;

    @Column(nullable = false, unique = true, length = 32)
    private String token;

    @Column(length = 120)
    private String label;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "view_count", nullable = false)
    private int viewCount;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected NoteShareEntity() {}

    public NoteShareEntity(NoteEntity note, UserEntity sharedBy, String token,
                           String label, Instant expiresAt) {
        this.note = note;
        this.sharedBy = sharedBy;
        this.token = token;
        this.label = label;
        this.expiresAt = expiresAt;
        this.viewCount = 0;
    }

    @PrePersist
    void ensureId() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    /** 是否仍可访问：未撤销且未过期。 */
    public boolean isAccessible(Instant now) {
        if (revokedAt != null) return false;
        return expiresAt == null || expiresAt.isAfter(now);
    }

    public String getId() { return id; }
    public NoteEntity getNote() { return note; }
    public UserEntity getSharedBy() { return sharedBy; }
    public String getToken() { return token; }
    public String getLabel() { return label; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getViewCount() { return viewCount; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setLabel(String label) { this.label = label; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void incrementViews() { this.viewCount = this.viewCount + 1; }
    public void revoke(Instant at) { this.revokedAt = at; }
}
