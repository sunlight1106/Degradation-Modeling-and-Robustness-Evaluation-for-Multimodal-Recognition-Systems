package com.robustvision.platform.repository;

import com.robustvision.platform.domain.KnowledgeTopicEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface KnowledgeTopicRepository extends JpaRepository<KnowledgeTopicEntity, Long> {

    /** 预置主题（owner 为空，所有用户可见） */
    List<KnowledgeTopicEntity> findByOwnerIsNullOrderBySortOrderAscIdAsc();

    /** 某用户自建主题 */
    List<KnowledgeTopicEntity> findByOwnerIdOrderBySortOrderAscIdAsc(Long ownerId);

    Optional<KnowledgeTopicEntity> findByIdAndOwnerIsNull(Long id);

    Optional<KnowledgeTopicEntity> findByIdAndOwnerId(Long id, Long ownerId);

    boolean existsByDomainAndNameAndOwnerIsNull(String domain, String name);

    boolean existsByDomainAndNameAndOwnerId(String domain, String name, Long ownerId);

    @Query("select distinct t.domain from KnowledgeTopicEntity t where t.owner is null order by t.domain asc")
    List<String> findDistinctDomainByOwnerIsNull();
}
