package com.robustvision.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recharge_order")
public class RechargeOrderEntity {
    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentMethod method;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RechargeStatus status = RechargeStatus.PENDING_VERIFICATION;

    @Column(name = "bank_last4", length = 4)
    private String bankLast4;

    @Column(name = "phone_masked", length = 32)
    private String phoneMasked;

    @Column(name = "verification_hash", length = 64)
    private String verificationHash;

    @Column(name = "verification_attempts", nullable = false)
    private int verificationAttempts;

    @Column(name = "payment_token_hash", unique = true, length = 64)
    private String paymentTokenHash;

    @Column(name = "qr_payload", length = 1000)
    private String qrPayload;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "paid_at")
    private Instant paidAt;

    protected RechargeOrderEntity() {}

    public RechargeOrderEntity(UserEntity user, PaymentMethod method, BigDecimal amount, RechargeStatus status,
                               String bankLast4, String phoneMasked, String verificationHash,
                               String paymentTokenHash, String qrPayload, Instant expiresAt) {
        this.user = user;
        this.method = method;
        this.amount = amount;
        this.status = status;
        this.bankLast4 = bankLast4;
        this.phoneMasked = phoneMasked;
        this.verificationHash = verificationHash;
        this.paymentTokenHash = paymentTokenHash;
        this.qrPayload = qrPayload;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void ensureId() { if (id == null) id = UUID.randomUUID().toString(); }

    public String getId() { return id; }
    public UserEntity getUser() { return user; }
    public PaymentMethod getMethod() { return method; }
    public BigDecimal getAmount() { return amount; }
    public RechargeStatus getStatus() { return status; }
    public String getBankLast4() { return bankLast4; }
    public String getPhoneMasked() { return phoneMasked; }
    public String getVerificationHash() { return verificationHash; }
    public int getVerificationAttempts() { return verificationAttempts; }
    public String getPaymentTokenHash() { return paymentTokenHash; }
    public String getQrPayload() { return qrPayload; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPaidAt() { return paidAt; }
    public void markPaid() { status = RechargeStatus.PAID; paidAt = Instant.now(); }
    public void markExpired() { status = RechargeStatus.EXPIRED; }
    public void recordInvalidCode() {
        verificationAttempts++;
        if (verificationAttempts >= 5) status = RechargeStatus.VERIFICATION_LOCKED;
    }
}
