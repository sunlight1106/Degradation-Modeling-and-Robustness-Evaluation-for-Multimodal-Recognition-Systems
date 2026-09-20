package com.robustvision.platform.domain;

public enum RechargeStatus {
    PENDING_PAYMENT,
    PENDING_VERIFICATION,
    VERIFICATION_LOCKED,
    PAID,
    EXPIRED,
    CANCELLED
}
