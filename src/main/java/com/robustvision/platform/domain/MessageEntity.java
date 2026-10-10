package com.robustvision.platform.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "internal_message")
public class MessageEntity {
    @Id @Column(length = 36)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private UserEntity sender;
    @Column(nullable = false, length = 180)
    private String subject;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;
    @Column(name = "workspace_id")
    private Long workspaceId;
    @Column(name = "reply_to_id", length = 36)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String replyToId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name="recalled_at") private Instant recalledAt;
    public Instant getRecalledAt() { return recalledAt; }
    public void recall() { body="消息已撤回"; recalledAt=Instant.now(); }

    protected MessageEntity() {}
    public MessageEntity(UserEntity sender, String subject, String body) { this.sender = sender; this.subject = subject; this.body = body; }
    @PrePersist void ensureId() { if (id == null) id = UUID.randomUUID().toString(); }
    public String getId() { return id; }
    public UserEntity getSender() { return sender; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public String getReplyToId() { return replyToId; }
    public void setReplyToId(String replyToId) { this.replyToId = replyToId; }
    public Instant getCreatedAt() { return createdAt; }
}
