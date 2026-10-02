package com.robustvision.platform.repository;

import com.robustvision.platform.domain.ModelProvider;
import com.robustvision.platform.domain.ProviderBudgetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;

public interface ProviderBudgetRepository extends JpaRepository<ProviderBudgetEntity, ModelProvider> {
    // MySQL and H2 MySQL-mode support this insert-if-absent operation. On an
    // existing row it is a no-op that still acquires the row's write lock.
    // Do not use INSERT IGNORE: that could mask unrelated data errors.
    @Modifying
    @Query(value = """
            INSERT INTO provider_budget (provider, monthly_budget_cny, used_cny, period_start, updated_at)
            VALUES (:provider, 500.0000, 0, :periodStart, CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE provider = :provider
            """, nativeQuery = true)
    int ensureExists(@Param("provider") String provider, @Param("periodStart") LocalDate periodStart);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select budget from ProviderBudgetEntity budget where budget.provider = :provider")
    Optional<ProviderBudgetEntity> findLockedByProvider(@Param("provider") ModelProvider provider);
}
