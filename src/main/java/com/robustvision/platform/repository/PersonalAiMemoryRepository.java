package com.robustvision.platform.repository;
import com.robustvision.platform.domain.PersonalAiMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface PersonalAiMemoryRepository extends JpaRepository<PersonalAiMemoryEntity,String> {
    List<PersonalAiMemoryEntity> findByOwnerIdOrderByCreatedAtAscIdAsc(Long owner);
    Optional<PersonalAiMemoryEntity> findByIdAndOwnerId(String id,Long owner);
}
