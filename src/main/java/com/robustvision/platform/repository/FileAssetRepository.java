package com.robustvision.platform.repository;

import com.robustvision.platform.domain.FileAssetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FileAssetRepository extends JpaRepository<FileAssetEntity, String> {
    boolean existsByIdAndOwnerId(String id, Long ownerId);
    List<FileAssetEntity> findAllByOrderByCreatedAtDesc();
    List<FileAssetEntity> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);
}

