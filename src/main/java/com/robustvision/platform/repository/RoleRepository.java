package com.robustvision.platform.repository;

import com.robustvision.platform.domain.RoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<RoleEntity, Long> {
    Optional<RoleEntity> findByCode(String code);
    boolean existsByCode(String code);
}
