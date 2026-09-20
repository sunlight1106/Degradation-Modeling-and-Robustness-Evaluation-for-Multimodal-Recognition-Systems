package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "workspace_member", uniqueConstraints = @UniqueConstraint(name = "uk_workspace_member", columnNames = {"workspace_id", "user_id"}))
public class WorkspaceMemberEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;
    @Enumerated(EnumType.STRING)
    @Column(name = "member_role", nullable = false, length = 20)
    private WorkspaceMemberRole role;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workspace_member_permission", joinColumns = @JoinColumn(name = "workspace_member_id"))
    @Column(name = "permission_code", length = 40)
    private Set<String> permissions = new LinkedHashSet<>();
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected WorkspaceMemberEntity() {}
    public WorkspaceMemberEntity(WorkspaceEntity workspace, UserEntity user, WorkspaceMemberRole role, Set<String> permissions) {
        this.workspace = workspace; this.user = user; this.role = role; this.permissions = new LinkedHashSet<>(permissions);
    }
    public Long getId() { return id; }
    public WorkspaceEntity getWorkspace() { return workspace; }
    public UserEntity getUser() { return user; }
    public WorkspaceMemberRole getRole() { return role; }
    public Set<String> getPermissions() { return permissions; }
    public Instant getCreatedAt() { return createdAt; }
    public void update(WorkspaceMemberRole role, Set<String> permissions) { this.role = role; this.permissions = new LinkedHashSet<>(permissions); }
}
