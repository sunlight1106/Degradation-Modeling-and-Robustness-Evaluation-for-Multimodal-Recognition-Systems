package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "contact_link", uniqueConstraints = @UniqueConstraint(columnNames = {"low_user_id", "high_user_id"}))
public class ContactLinkEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "low_user_id", nullable = false) private Long lowUserId;
    @Column(name = "high_user_id", nullable = false) private Long highUserId;
    @Column(name = "requester_id", nullable = false) private Long requesterId;
    @Column(nullable = false, length = 16) private String status = "PENDING";
    @Column(name = "low_blocked", nullable = false) private boolean lowBlocked;
    @Column(name = "high_blocked", nullable = false) private boolean highBlocked;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    protected ContactLinkEntity() {}
    public ContactLinkEntity(long a, long b, long requester) { lowUserId = Math.min(a, b); highUserId = Math.max(a, b); requesterId = requester; }
    public Long getId() { return id; }
    public Long getLowUserId() { return lowUserId; }
    public Long getHighUserId() { return highUserId; }
    public Long getRequesterId() { return requesterId; }
    public String getStatus() { return status; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean includes(long user) { return lowUserId == user || highUserId == user; }
    public long peer(long user) { return lowUserId == user ? highUserId : lowUserId; }
    public boolean blocked() { return lowBlocked || highBlocked; }
    public boolean blockedBy(long user) { return lowUserId == user ? lowBlocked : highBlocked; }
    public void request(long user) { requesterId = user; setStatus("PENDING"); }
    public void setStatus(String value) { status = value; updatedAt = Instant.now(); }
    public void block(long user, boolean value) { if (lowUserId == user) lowBlocked = value; else highBlocked = value; setStatus("REMOVED"); }
}
