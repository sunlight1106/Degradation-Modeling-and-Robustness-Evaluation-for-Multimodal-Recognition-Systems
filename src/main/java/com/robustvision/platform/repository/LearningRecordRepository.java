package com.robustvision.platform.repository;
import com.robustvision.platform.domain.LearningRecordEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface LearningRecordRepository extends JpaRepository<LearningRecordEntity,String> {
    List<LearningRecordEntity> findByOwnerIdAndKindOrderByUpdatedAtDesc(Long owner,String kind,Pageable page);
    Optional<LearningRecordEntity> findByIdAndOwnerId(String id,Long owner);
    long countByOwnerIdAndKind(Long owner,String kind);
}
