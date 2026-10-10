package com.robustvision.platform.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="group_invitation",uniqueConstraints=@UniqueConstraint(columnNames={"workspace_id","target_id"}))
public class GroupInvitationEntity {
 @Id @Column(length=36) private String id;
 @Column(name="workspace_id",nullable=false) private Long workspaceId;
 @Column(name="target_id",nullable=false) private Long targetId;
 @Column(name="actor_id",nullable=false) private Long actorId;
 @Column(nullable=false,length=12) private String kind;
 @Column(nullable=false,length=12) private String status;
 @Column(nullable=false,length=12) private String role;
 @Column(name="expires_at",nullable=false) private Instant expiresAt;
 @Column(name="created_at",nullable=false) private Instant createdAt;
}
