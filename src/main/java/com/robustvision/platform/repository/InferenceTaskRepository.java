package com.robustvision.platform.repository;

import com.robustvision.platform.domain.InferenceTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InferenceTaskRepository extends JpaRepository<InferenceTaskEntity, String> {
    List<InferenceTaskEntity> findAllByOrderByCreatedAtDesc();
    List<InferenceTaskEntity> findByRequestedByIdOrderByCreatedAtDesc(Long requestedBy);
}
