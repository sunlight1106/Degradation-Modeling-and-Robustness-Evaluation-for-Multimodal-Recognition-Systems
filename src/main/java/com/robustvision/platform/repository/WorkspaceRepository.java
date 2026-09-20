package com.robustvision.platform.repository;

import com.robustvision.platform.domain.WorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, Long> {
    boolean existsBySlug(String slug);
    Optional<WorkspaceEntity> findBySlug(String slug);
}
