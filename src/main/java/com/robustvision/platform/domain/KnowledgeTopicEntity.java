package com.robustvision.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 跨学科知识主题。domain 表示学科领域（如生物化学医学、计算机、数学），
 * builtin 为 true 时表示平台预置主题，owner 为空表示对所有用户可见。
 */
@Entity
@Table(name = "knowledge_topic")
public class KnowledgeTopicEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String domain;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

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

    protected KnowledgeTopicEntity() {}

    public KnowledgeTopicEntity(String domain, String name, String description,
                                boolean builtin, UserEntity owner, int sortOrder) {
        this.domain = domain;
        this.name = name;
        this.description = description;
        this.builtin = builtin;
        this.owner = owner;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public String getDomain() { return domain; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isBuiltin() { return builtin; }
    public UserEntity getOwner() { return owner; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setDomain(String domain) { this.domain = domain; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
