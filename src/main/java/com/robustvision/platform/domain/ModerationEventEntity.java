package com.robustvision.platform.domain;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="moderation_event")
public class ModerationEventEntity {
 @Id @Column(length=36) private String id;
 @Column(name="report_id",nullable=false,length=36) private String reportId;
 @Column(name="actor_id") private Long actorId;
 @Column(nullable=false,length=30) private String action;
 @Column(nullable=false,length=2000) private String detail;
 @Column(name="created_at",nullable=false) private Instant createdAt;
}
