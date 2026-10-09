package com.robustvision.platform.repository;
import com.robustvision.platform.domain.AdminAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AdminAuditRepository extends JpaRepository<AdminAuditEntity, Long> {}
