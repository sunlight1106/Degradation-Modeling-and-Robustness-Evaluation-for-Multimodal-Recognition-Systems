package com.robustvision.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "user_wallet", uniqueConstraints = @UniqueConstraint(name = "uk_wallet_user", columnNames = "user_id"))
public class WalletEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "balance_cny", nullable = false, precision = 14, scale = 4)
    private BigDecimal balanceCny;

    @Column(name = "monthly_quota_cny", nullable = false, precision = 14, scale = 4)
    private BigDecimal monthlyQuotaCny;

    @Column(name = "month_spent_cny", nullable = false, precision = 14, scale = 4)
    private BigDecimal monthSpentCny = BigDecimal.ZERO;

    @Column(name = "quota_period_start", nullable = false)
    private LocalDate quotaPeriodStart;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected WalletEntity() {}

    public WalletEntity(UserEntity user, BigDecimal balanceCny, BigDecimal monthlyQuotaCny) {
        this.user = user;
        this.balanceCny = balanceCny;
        this.monthlyQuotaCny = monthlyQuotaCny;
        this.quotaPeriodStart = LocalDate.now().withDayOfMonth(1);
    }

    public Long getId() { return id; }
    public UserEntity getUser() { return user; }
    public BigDecimal getBalanceCny() { return balanceCny; }
    public BigDecimal getMonthlyQuotaCny() { return monthlyQuotaCny; }
    public BigDecimal getMonthSpentCny() { return monthSpentCny; }
    public LocalDate getQuotaPeriodStart() { return quotaPeriodStart; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void resetPeriodIfNeeded(LocalDate today) {
        LocalDate start = today.withDayOfMonth(1);
        if (!start.equals(quotaPeriodStart)) {
            quotaPeriodStart = start;
            monthSpentCny = BigDecimal.ZERO;
            updatedAt = Instant.now();
        }
    }

    public void credit(BigDecimal amount) {
        balanceCny = balanceCny.add(amount);
        updatedAt = Instant.now();
    }

    public void debit(BigDecimal amount) {
        balanceCny = balanceCny.subtract(amount).max(BigDecimal.ZERO);
        monthSpentCny = monthSpentCny.add(amount);
        updatedAt = Instant.now();
    }

    public void adjustBalance(BigDecimal delta) {
        balanceCny = balanceCny.add(delta).max(BigDecimal.ZERO);
        updatedAt = Instant.now();
    }

    public void setMonthlyQuotaCny(BigDecimal quota) {
        monthlyQuotaCny = quota;
        updatedAt = Instant.now();
    }
}
