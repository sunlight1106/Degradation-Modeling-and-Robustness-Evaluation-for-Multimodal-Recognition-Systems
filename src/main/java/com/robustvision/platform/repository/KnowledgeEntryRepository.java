package com.robustvision.platform.repository;

import com.robustvision.platform.domain.KnowledgeEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntryEntity, String> {

    List<KnowledgeEntryEntity> findByTopicIdOrderBySortOrderAscCreatedAtAsc(Long topicId);

    Optional<KnowledgeEntryEntity> findByIdAndOwnerIsNull(String id);

    Optional<KnowledgeEntryEntity> findByIdAndOwnerId(String id, Long ownerId);

    boolean existsByTopicIdAndTitleAndOwnerIsNull(Long topicId, String title);

    long countByTopicId(Long topicId);

    /** 按标题、摘要、正文或标签做模糊搜索，限定在预置卡或某用户自建卡范围内。 */
    @Query("""
            SELECT e FROM KnowledgeEntryEntity e
            WHERE (e.owner IS NULL OR e.owner.id = :ownerId)
              AND (LOWER(e.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(e.summary) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(e.body) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(e.tags) LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY e.updatedAt DESC
            """)
    List<KnowledgeEntryEntity> search(@Param("ownerId") Long ownerId, @Param("keyword") String keyword);

    /** 统计引用了某知识卡的笔记数量，用于双向链接展示。 */
    @Query("""
            SELECT COUNT(r) FROM NoteReferenceEntity r
            WHERE r.referenceType = com.robustvision.platform.domain.NoteReferenceType.ENTRY
              AND r.referenceId = :entryId
            """)
    long countNoteReferences(@Param("entryId") String entryId);
}
