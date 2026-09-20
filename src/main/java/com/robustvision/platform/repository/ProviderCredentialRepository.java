package com.robustvision.platform.repository;

import com.robustvision.platform.domain.ModelProvider;
import com.robustvision.platform.domain.ProviderCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProviderCredentialRepository extends JpaRepository<ProviderCredentialEntity, Long> {
    List<ProviderCredentialEntity> findByActiveTrueOrderByCreatedAtAsc();
    List<ProviderCredentialEntity> findByProviderAndActiveTrueOrderByCreatedAtAsc(ModelProvider provider);
    long countByProviderAndActiveTrue(ModelProvider provider);
}
