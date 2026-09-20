package com.robustvision.platform.repository;

import com.robustvision.platform.domain.WalletEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface WalletRepository extends JpaRepository<WalletEntity, Long> {
    Optional<WalletEntity> findByUserId(Long userId);
    List<WalletEntity> findAllByOrderByUpdatedAtDesc();
}
