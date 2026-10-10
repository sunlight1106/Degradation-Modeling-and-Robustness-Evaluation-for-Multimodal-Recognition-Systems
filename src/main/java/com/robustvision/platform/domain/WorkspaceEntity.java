package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "workspace")
public class WorkspaceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, unique = true, length = 100)
    private String slug;
    @Column(nullable = false, length = 20)
    private String color;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserEntity owner;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
    @org.hibernate.annotations.ColumnDefault("false") @Column(nullable=false) private boolean archived;
    @org.hibernate.annotations.ColumnDefault("false") @Column(nullable=false) private boolean dissolved;
    @org.hibernate.annotations.ColumnDefault("false") @Column(name="accept_requests",nullable=false) private boolean acceptRequests;
    public boolean isArchived() { return archived; }
    public boolean isDissolved() { return dissolved; }
    public boolean isAcceptRequests() { return acceptRequests; }
    public void lifecycle(boolean archived,boolean requests) { this.archived=archived; this.acceptRequests=requests; this.updatedAt=Instant.now(); }
    public void dissolve() { dissolved=true; lifecycle(true,false); }
    public void transfer(UserEntity owner) { this.owner=owner; updatedAt=Instant.now(); }

    protected WorkspaceEntity() {}
    public WorkspaceEntity(String name, String slug, String color, UserEntity owner) {
        this.name = name; this.slug = slug; this.color = color; this.owner = owner;
    }
    public Long getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getColor() { return color; }
    public UserEntity getOwner() { return owner; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void update(String name, String color) { this.name = name; this.color = color; this.updatedAt = Instant.now(); }
}
