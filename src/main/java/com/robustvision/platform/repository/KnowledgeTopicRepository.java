package com.robustvision.platform.repository;

import com.robustvision.platform.domain.KnowledgeTopicEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KnowledgeTopicRepository extends JpaRepository<KnowledgeTopicEntity, Long> {

    /** Keep the builtin-first ordering used when listing all cards. */
    @Query("""
            select t from KnowledgeTopicEntity t
            where t.owner is null or t.owner.id = :ownerId
            order by case when t.owner is null then 0 else 1 end, t.sortOrder, t.id
            """)
    List<KnowledgeTopicEntity> findVisibleTopics(@Param("ownerId") Long ownerId);

    @Query("""
            select t from KnowledgeTopicEntity t
            where t.id = :id and (t.owner is null or t.owner.id = :ownerId)
            """)
    Optional<KnowledgeTopicEntity> findReadableTopic(@Param("id") Long id, @Param("ownerId") Long ownerId);

    @Query("select coalesce(max(t.sortOrder), 0) from KnowledgeTopicEntity t where t.owner.id = :ownerId")
    int findMaxSortOrder(@Param("ownerId") Long ownerId);

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
