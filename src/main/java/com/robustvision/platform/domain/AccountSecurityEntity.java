package com.robustvision.platform.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="account_security")
public class AccountSecurityEntity {
 @Id @Column(name="owner_id") private Long ownerId;
 @Column(name="verified_email",length=160) private String verifiedEmail;
 @Column(name="mfa_secret",columnDefinition="TEXT") private String mfaSecret;
 @Column(name="pending_secret",columnDefinition="TEXT") private String pendingSecret;
 @Column(name="pending_until") private Instant pendingUntil;
 @Column(name="last_counter",nullable=false) private long lastCounter;
 @Column(name="recovery_hashes",nullable=false,columnDefinition="TEXT") private String recoveryHashes;
 @Column(name="failed_attempts",nullable=false) private int failedAttempts;
 @Column(name="locked_until") private Instant lockedUntil;
}
