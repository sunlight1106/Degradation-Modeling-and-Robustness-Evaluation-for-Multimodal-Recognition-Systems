package com.robustvision.platform.domain;
import jakarta.persistence.*;
@Entity @Table(name="account_closure")
public class AccountClosureEntity {
 @Id @Column(name="owner_id") private Long ownerId;
 @Column(nullable=false,length=20) private String mode;
 @Column(nullable=false) private boolean discoverable;
 @Column(name="closed_at",nullable=false) private java.time.Instant closedAt;
}
