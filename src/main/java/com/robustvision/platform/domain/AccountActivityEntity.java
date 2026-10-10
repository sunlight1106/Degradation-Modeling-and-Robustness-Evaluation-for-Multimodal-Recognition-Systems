package com.robustvision.platform.domain;
import jakarta.persistence.*;
@Entity @Table(name="account_activity")
public class AccountActivityEntity {
 @Id @Column(length=36) private String id;
 @Column(name="owner_id") private Long ownerId;
 @Column(nullable=false,length=30) private String action;
 @Column(nullable=false,length=40) private String outcome;
 @Column(nullable=false,length=80) private String network;
 @Column(nullable=false,length=180) private String device;
 @Column(name="created_at",nullable=false) private java.time.Instant createdAt;
}
