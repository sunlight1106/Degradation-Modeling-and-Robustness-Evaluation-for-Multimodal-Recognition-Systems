package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "admin_audit")
public class AdminAuditEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "operator_id", nullable = false) private Long operatorId;
    @Column(name = "operator_name", nullable = false, length = 60) private String operatorName;
    @Column(nullable = false, length = 40) private String action;
    @Column(name = "target_type", nullable = false, length = 20) private String targetType;
    @Column(name = "target_id", nullable = false) private Long targetId;
    @Column(nullable = false, length = 12000) private String detail;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    protected AdminAuditEntity() {}
    public AdminAuditEntity(UserEntity operator, String action, String targetType, Long targetId, String detail) {
        this.operatorId = operator.getId(); this.operatorName = operator.getUsername(); this.action = action;
        this.targetType = targetType; this.targetId = targetId; this.detail = detail;
    }
    public Long getId() { return id; }
    public Long getOperatorId() { return operatorId; }
    public String getOperatorName() { return operatorName; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
