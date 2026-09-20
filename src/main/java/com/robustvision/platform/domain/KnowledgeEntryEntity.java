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

/** 知识卡。body 为 Markdown 正文，可关联到具体主题，支持双向链接到笔记。 */
@Entity
@Table(name = "knowledge_entry")
public class KnowledgeEntryEntity {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "topic_id", nullable = false)
    private KnowledgeTopicEntity topic;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(length = 500)
    private String summary;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(length = 500)
    private String tags;

    @Column(nullable = false)
    private boolean builtin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private UserEntity owner;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected KnowledgeEntryEntity() {}

    public KnowledgeEntryEntity(KnowledgeTopicEntity topic, String title, String summary,
                                String body, String tags, boolean builtin,
                                UserEntity owner, int sortOrder) {
        this.topic = topic;
        this.title = title;
        this.summary = summary;
        this.body = body;
        this.tags = tags;
        this.builtin = builtin;
        this.owner = owner;
        this.sortOrder = sortOrder;
    }

    @PrePersist
    void ensureId() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public KnowledgeTopicEntity getTopic() { return topic; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public String getBody() { return body; }
    public String getTags() { return tags; }
    public boolean isBuiltin() { return builtin; }
    public UserEntity getOwner() { return owner; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setTopic(KnowledgeTopicEntity topic) { this.topic = topic; }
    public void setTitle(String title) { this.title = title; }
    public void setSummary(String summary) { this.summary = summary; }
    public void setBody(String body) { this.body = body; }
    public void setTags(String tags) { this.tags = tags; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
