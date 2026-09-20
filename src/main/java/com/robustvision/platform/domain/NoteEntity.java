package com.robustvision.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Markdown 笔记。body 为原始 Markdown，渲染与消毒在前端完成，导出在后端完成。 */
@Entity
@Table(name = "note")
public class NoteEntity {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserEntity owner;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String body;

    @Column(length = 500)
    private String tags;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NoteStatus status = NoteStatus.DRAFT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected NoteEntity() {}

    public NoteEntity(UserEntity owner, String title, String body, String tags, NoteStatus status) {
        this.owner = owner;
        this.title = title;
        this.body = body;
        this.tags = tags;
        if (status != null) this.status = status;
    }

    @PrePersist
    void ensureId() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public UserEntity getOwner() { return owner; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getTags() { return tags; }
    public NoteStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setTitle(String title) { this.title = title; }
    public void setBody(String body) { this.body = body; }
    public void setTags(String tags) { this.tags = tags; }
    public void setStatus(NoteStatus status) { this.status = status; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
