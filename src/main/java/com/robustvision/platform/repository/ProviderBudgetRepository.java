package com.robustvision.platform.repository;

import com.robustvision.platform.domain.ModelProvider;
import com.robustvision.platform.domain.ProviderBudgetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderBudgetRepository extends JpaRepository<ProviderBudgetEntity, ModelProvider> {}
