package com.robustvision.platform.domain;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="moderation_report",uniqueConstraints=@UniqueConstraint(columnNames={"source_type","source_id","reporter_id"}))
public class ModerationReportEntity {
 @Id @Column(length=36) private String id;
 @Column(name="source_type",nullable=false,length=12) private String sourceType;
 @Column(name="source_id",nullable=false,length=36) private String sourceId;
 @Column(name="reporter_id",nullable=false) private Long reporterId;
 @Column(name="target_id",nullable=false) private Long targetId;
 @Column(name="target_name",nullable=false,length=80) private String targetName;
 @Column(name="target_identity",nullable=false,length=36) private String targetIdentity;
 @Column(nullable=false,columnDefinition="TEXT") private String evidence;
 @Column(nullable=false,length=500) private String reason;
 @Column(nullable=false,length=12) private String status;
 @Column(nullable=false) private boolean urgent;
 @Column(name="review_reason",length=1000) private String reviewReason;
 @Column(name="reviewer_id") private Long reviewerId;
 @Column(name="created_at",nullable=false) private Instant createdAt;
 @Column(name="reviewed_at") private Instant reviewedAt;
}
