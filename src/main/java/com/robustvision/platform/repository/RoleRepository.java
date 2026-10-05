package com.robustvision.platform.repository;

import com.robustvision.platform.domain.RoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<RoleEntity, Long> {
    /** Global mutex for account/role administration; acquire before any user row. */
    @Query(value = "SELECT id FROM app_role WHERE code = 'ADMIN' FOR UPDATE", nativeQuery = true)
    Optional<Long> lockAdminGuard();
    Optional<RoleEntity> findByCode(String code);
    boolean existsByCode(String code);
}
