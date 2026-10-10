package com.robustvision.platform.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="account_challenge")
public class AccountChallengeEntity {
 @Id @Column(length=36) private String id;
 @Column(name="owner_id",nullable=false) private Long ownerId;
 @Column(nullable=false,length=12) private String purpose;
 @Column(nullable=false,length=160) private String email;
 @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR) @Column(name="token_hash",nullable=false,length=64,columnDefinition="CHAR(64)") private String tokenHash;
 @Column(name="expires_at",nullable=false) private Instant expiresAt;
 @Column(name="created_at",nullable=false) private Instant createdAt;
 @Column(name="context_hash",length=64) private String contextHash;
 @Column(nullable=false) private boolean consumed;
 @Column(nullable=false,length=12) private String delivery;
}
