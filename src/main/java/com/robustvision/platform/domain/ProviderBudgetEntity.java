package com.robustvision.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "provider_budget")
public class ProviderBudgetEntity {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ModelProvider provider;

    @Column(name = "monthly_budget_cny", nullable = false, precision = 14, scale = 4)
    private BigDecimal monthlyBudgetCny;

    @Column(name = "used_cny", nullable = false, precision = 14, scale = 4)
    private BigDecimal usedCny = BigDecimal.ZERO;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ProviderBudgetEntity() {}

    public ProviderBudgetEntity(ModelProvider provider, BigDecimal monthlyBudgetCny) {
        this.provider = provider;
        this.monthlyBudgetCny = monthlyBudgetCny;
        this.periodStart = LocalDate.now().withDayOfMonth(1);
    }

    public ModelProvider getProvider() { return provider; }
    public BigDecimal getMonthlyBudgetCny() { return monthlyBudgetCny; }
    public BigDecimal getUsedCny() { return usedCny; }
    public LocalDate getPeriodStart() { return periodStart; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void addUsage(BigDecimal amount) {
        resetPeriodIfNeeded();
        usedCny = usedCny.add(amount);
        updatedAt = Instant.now();
    }
    public void resetPeriodIfNeeded() {
        LocalDate start = LocalDate.now().withDayOfMonth(1);
        if (!start.equals(periodStart)) { periodStart = start; usedCny = BigDecimal.ZERO; updatedAt = Instant.now(); }
    }
    public void setMonthlyBudgetCny(BigDecimal value) { monthlyBudgetCny = value; updatedAt = Instant.now(); }
}
