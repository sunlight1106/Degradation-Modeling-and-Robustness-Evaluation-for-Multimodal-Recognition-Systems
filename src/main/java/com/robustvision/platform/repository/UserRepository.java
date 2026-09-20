package com.robustvision.platform.repository;

import com.robustvision.platform.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByEmailAndIdNot(String email, Long id);
    Optional<UserEntity> findByEmail(String email);
    List<UserEntity> findAllByOrderByCreatedAtDesc();
    long countByRoleCodeAndStatus(String roleCode, com.robustvision.platform.domain.UserStatus status);
}
