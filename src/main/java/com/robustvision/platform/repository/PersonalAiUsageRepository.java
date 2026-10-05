package com.robustvision.platform.repository;
import com.robustvision.platform.domain.PersonalAiUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PersonalAiUsageRepository extends JpaRepository<PersonalAiUsageEntity, String> {
    List<PersonalAiUsageEntity> findTop100ByOwnerIdOrderByCreatedAtDesc(Long ownerId);
}
