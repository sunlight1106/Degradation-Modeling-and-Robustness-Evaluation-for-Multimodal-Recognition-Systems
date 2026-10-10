package com.robustvision.platform.domain;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="moderation_penalty")
public class ModerationPenaltyEntity {
 @Id @Column(length=36) private String id;
 @Column(name="report_id",nullable=false,length=36) private String reportId;
 @Column(name="target_id",nullable=false) private Long targetId;
 @Column(name="actor_id") private Long actorId;
 @Column(nullable=false,length=12) private String kind;
 @Column(nullable=false,length=200) private String features;
 @Column(nullable=false,length=1000) private String reason;
 @Column(nullable=false) private boolean automatic;
 @Column(name="created_at",nullable=false) private Instant createdAt;
 @Column(name="expires_at",nullable=false) private Instant expiresAt;
 @Column(name="revoked_at") private Instant revokedAt;
 @Column(length=1000) private String appeal;
 @Column(name="appeal_at") private Instant appealAt;
 @Column(name="appeal_reply",length=1000) private String appealReply;
}
