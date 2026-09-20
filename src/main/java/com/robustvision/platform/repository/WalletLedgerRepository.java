package com.robustvision.platform.repository;

import com.robustvision.platform.domain.WalletLedgerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WalletLedgerRepository extends JpaRepository<WalletLedgerEntity, Long> {
    List<WalletLedgerEntity> findTop12ByUserIdOrderByCreatedAtDesc(Long userId);
}
