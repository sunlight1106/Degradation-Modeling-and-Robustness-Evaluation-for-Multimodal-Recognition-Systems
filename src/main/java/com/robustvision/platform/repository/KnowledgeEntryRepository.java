package com.robustvision.platform.repository;

import com.robustvision.platform.domain.KnowledgeEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntryEntity, String> {

    interface TopicEntryTitle { Long getTopicId(); String getTitle(); }

    /** Bootstrap only needs identities, never every existing card body or entity. */
    @Query("select e.topic.id as topicId, e.title as title from KnowledgeEntryEntity e where e.topic.id in :topicIds")
    List<TopicEntryTitle> findTitlesByTopicIds(@Param("topicIds") Collection<Long> topicIds);

    @Query("""
            select e from KnowledgeEntryEntity e join fetch e.topic
            where e.topic.id = :topicId order by e.sortOrder, e.createdAt, e.id
            """)
    List<KnowledgeEntryEntity> findByTopicIdOrderBySortOrderAscCreatedAtAsc(@Param("topicId") Long topicId);

    /** Listing follows topic visibility, matching the original topic-by-topic traversal. */
    @Query("""
            select e from KnowledgeEntryEntity e join fetch e.topic t
            where (t.owner is null or t.owner.id = :ownerId)
              and (e.owner is null or e.owner.id = :ownerId)
            order by case when t.owner is null then 0 else 1 end,
                     t.sortOrder, t.id, e.sortOrder, e.createdAt, e.id
            """)
    List<KnowledgeEntryEntity> findInVisibleTopics(@Param("ownerId") Long ownerId);

    @Query("""
            select e from KnowledgeEntryEntity e join fetch e.topic
            where e.topic.id = :topicId and (e.owner is null or e.owner.id = :ownerId)
              and (e.topic.owner is null or e.topic.owner.id = :ownerId)
            order by e.sortOrder, e.createdAt, e.id
            """)
    List<KnowledgeEntryEntity> findReadableInTopic(@Param("topicId") Long topicId, @Param("ownerId") Long ownerId);

    @Query("""
            select count(e) > 0 from KnowledgeEntryEntity e
            where e.id = :id and (e.owner is null or e.owner.id = :ownerId)
              and (e.topic.owner is null or e.topic.owner.id = :ownerId)
            """)
    boolean isReadable(@Param("id") String id, @Param("ownerId") Long ownerId);

    interface TopicEntryCount {
        Long getTopicId();
        long getEntryCount();
    }

    @Query("""
            select e.topic.id as topicId, count(e) as entryCount from KnowledgeEntryEntity e
            where e.topic.id in :topicIds group by e.topic.id
            """)
    List<TopicEntryCount> countByTopicIds(@Param("topicIds") Collection<Long> topicIds);

    interface EntryReferenceCount {
        String getEntryId();
        long getReferenceCount();
    }

    @Query("""
            select r.referenceId as entryId, count(r) as referenceCount from NoteReferenceEntity r
            where r.referenceType = com.robustvision.platform.domain.NoteReferenceType.ENTRY
              and r.referenceId in :entryIds
            group by r.referenceId
            """)
    List<EntryReferenceCount> countNoteReferencesByEntryIds(@Param("entryIds") Collection<String> entryIds);

    Optional<KnowledgeEntryEntity> findByIdAndOwnerIsNull(String id);

    Optional<KnowledgeEntryEntity> findByIdAndOwnerId(String id, Long ownerId);

    boolean existsByTopicIdAndTitleAndOwnerIsNull(Long topicId, String title);

    long countByTopicId(Long topicId);

    /** 按标题、摘要、正文或标签做模糊搜索，限定在预置卡或某用户自建卡范围内。 */
    @Query("""
            SELECT e FROM KnowledgeEntryEntity e JOIN FETCH e.topic
            WHERE (e.owner IS NULL OR e.owner.id = :ownerId)
              AND (e.topic.owner IS NULL OR e.topic.owner.id = :ownerId)
              AND (:topicId IS NULL OR e.topic.id = :topicId)
              AND (LOWER(e.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(e.summary) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(e.body) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(e.tags) LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY e.updatedAt DESC, e.id ASC
            """)
    List<KnowledgeEntryEntity> search(@Param("ownerId") Long ownerId, @Param("keyword") String keyword,
                                      @Param("topicId") Long topicId);

    default List<KnowledgeEntryEntity> search(Long ownerId, String keyword) {
        return search(ownerId, keyword, null);
    }

    /** 统计引用了某知识卡的笔记数量，用于双向链接展示。 */
    @Query("""
            SELECT COUNT(r) FROM NoteReferenceEntity r
            WHERE r.referenceType = com.robustvision.platform.domain.NoteReferenceType.ENTRY
              AND r.referenceId = :entryId
            """)
    long countNoteReferences(@Param("entryId") String entryId);
}
