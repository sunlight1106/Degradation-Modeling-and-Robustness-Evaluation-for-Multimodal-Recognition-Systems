package com.robustvision.platform.repository;
import com.robustvision.platform.domain.PersonalRecognitionResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface PersonalRecognitionResultRepository extends JpaRepository<PersonalRecognitionResultEntity,String> {
    List<PersonalRecognitionResultEntity> findTop100ByOwnerIdOrderByCreatedAtDesc(Long ownerId);
    Optional<PersonalRecognitionResultEntity> findByIdAndOwnerId(String id,Long ownerId);
}
