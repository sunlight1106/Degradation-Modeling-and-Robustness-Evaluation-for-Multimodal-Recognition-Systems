package com.robustvision.platform.repository;

import com.robustvision.platform.domain.ModelDefinitionEntity;
import com.robustvision.platform.domain.ModelStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModelDefinitionRepository extends JpaRepository<ModelDefinitionEntity, Long> {
    boolean existsByCodeAndVersion(String code, String version);
    List<ModelDefinitionEntity> findByStatusOrderByNameAsc(ModelStatus status);
}

