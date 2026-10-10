package com.robustvision.platform.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="group_report",uniqueConstraints=@UniqueConstraint(columnNames={"message_id","reporter_id"}))
public class GroupReportEntity {
 @Id @Column(length=36) private String id;
 @Column(name="workspace_id",nullable=false) private Long workspaceId;
 @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR) @Column(name="message_id",nullable=false,length=36,columnDefinition="CHAR(36)") private String messageId;
 @Column(name="reporter_id",nullable=false) private Long reporterId;
 @Column(nullable=false,length=500) private String reason;
 @Column(nullable=false,length=12) private String status;
 @Column(name="created_at",nullable=false) private Instant createdAt;
}
