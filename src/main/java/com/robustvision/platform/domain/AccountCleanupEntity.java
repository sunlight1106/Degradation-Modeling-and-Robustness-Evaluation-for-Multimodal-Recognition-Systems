package com.robustvision.platform.domain;
import jakarta.persistence.*;
@Entity @Table(name="account_cleanup")
public class AccountCleanupEntity {
 @Id @Column(length=36) private String id;
 @Column(name="owner_id",nullable=false) private Long ownerId;
 @Column(nullable=false,length=20) private String kind;
 @Column(nullable=false,length=600) private String resource;
 @Column(nullable=false) private int attempts;
 @Column(name="retry_at",nullable=false) private java.time.Instant retryAt;
}
