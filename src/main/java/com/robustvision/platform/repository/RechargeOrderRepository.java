package com.robustvision.platform.repository;

import com.robustvision.platform.domain.RechargeOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface RechargeOrderRepository extends JpaRepository<RechargeOrderEntity, String> {
    List<RechargeOrderEntity> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select recharge from RechargeOrderEntity recharge where recharge.id = :id")
    Optional<RechargeOrderEntity> findLockedById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select recharge from RechargeOrderEntity recharge where recharge.paymentTokenHash = :tokenHash")
    Optional<RechargeOrderEntity> findLockedByPaymentTokenHash(@Param("tokenHash") String tokenHash);

    Optional<RechargeOrderEntity> findByPaymentTokenHash(String tokenHash);
}
