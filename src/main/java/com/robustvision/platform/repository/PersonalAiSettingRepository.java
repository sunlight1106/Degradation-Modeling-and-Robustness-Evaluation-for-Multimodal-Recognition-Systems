package com.robustvision.platform.repository;
import com.robustvision.platform.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface PersonalAiSettingRepository extends JpaRepository<PersonalAiSettingEntity, String> {
    Optional<PersonalAiSettingEntity> findByOwnerIdAndProvider(Long ownerId, AiProvider provider);
    List<PersonalAiSettingEntity> findByOwnerIdOrderByProviderAsc(Long ownerId);
}
