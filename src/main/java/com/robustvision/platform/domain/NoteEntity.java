package com.robustvision.platform.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

/** 私有学习笔记。保存 Markdown 或 HTML 源文，显示与导出分别进行安全处理。 */
@Entity
@Table(name = "note")
@org.hibernate.annotations.SQLRestriction("deleted_at IS NULL")
public class NoteEntity {

    @Id
    @Column(length = 36)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserEntity owner;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String body;

    @Column(name = "parent_id", length = 36, columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String parentId;

    @Column(length = 500)
    private String tags;

    @Column(nullable = false, length = 40)
    private String library = "综合学习";

    @Column(name = "content_format", nullable = false, length = 12)
    private String contentFormat = "MARKDOWN";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NoteStatus status = NoteStatus.DRAFT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(nullable = false) private long revision;
    public long getRevision() { return revision; }
    public void incrementRevision() { revision++; }
    public void assignClientId(String value) { id = value; }
    @Column(name="deleted_at") private Instant deletedAt;
    public void trash() { deletedAt=Instant.now(); incrementRevision(); }
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

    public String getParentId() { return parentId; }
    public void setParentId(String id) { parentId = id; }

    public String getLibrary() { return library; }
    public void setLibrary(String library) { this.library = library; }
    public String getContentFormat() { return contentFormat; }
    public void setContentFormat(String format) { this.contentFormat = format; }

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
