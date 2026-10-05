package com.robustvision.platform.repository;

import com.robustvision.platform.domain.InferenceStatus;
import com.robustvision.platform.domain.NoteReferenceEntity;
import com.robustvision.platform.domain.NoteReferenceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface NoteReferenceRepository extends JpaRepository<NoteReferenceEntity, Long> {

    List<NoteReferenceEntity> findByNoteIdOrderBySortOrderAscIdAsc(String noteId);

    void deleteByNoteId(String noteId);

    /** 反查引用了某目标的笔记引用记录，用于双向链接。 */
    List<NoteReferenceEntity> findByReferenceTypeAndReferenceId(NoteReferenceType referenceType, String referenceId);

    boolean existsByNoteIdAndReferenceTypeAndReferenceId(String noteId, NoteReferenceType referenceType, String referenceId);

    interface ReferenceSummary {
        Long getId();
        NoteReferenceType getReferenceType();
        String getReferenceId();
        String getLabel();
    }

    @Query("""
            select r.id as id, r.referenceType as referenceType,
                   r.referenceId as referenceId, r.label as label
            from NoteReferenceEntity r where r.note.id = :noteId
            order by r.sortOrder, r.id
            """)
    List<ReferenceSummary> findSummariesByNoteId(@Param("noteId") String noteId);

    interface FileTargetSummary {
        Long getReferenceRowId();
        String getOriginalName();
        String getContentType();
        long getSizeBytes();
        String getSha256();
    }

    // Match IDs inside the database rather than in a Java String map: this retains
    // the database's ID comparison/collation semantics (including MySQL CHAR IDs).
    @Query("""
            select r.id as referenceRowId, f.originalName as originalName,
                   f.contentType as contentType, f.sizeBytes as sizeBytes, f.sha256 as sha256
            from NoteReferenceEntity r, FileAssetEntity f
            where r.id in :referenceIds and f.id = r.referenceId
              and f.owner.id = r.note.owner.id
              and r.referenceType = com.robustvision.platform.domain.NoteReferenceType.FILE
            """)
    List<FileTargetSummary> findFileTargets(@Param("referenceIds") Collection<Long> referenceIds);

    interface TaskTargetSummary {
        Long getReferenceRowId();
        String getTraceId();
        String getModelName();
        InferenceStatus getStatus();
        BigDecimal getCostCny();
    }

    @Query("""
            select r.id as referenceRowId, t.traceId as traceId, t.model.name as modelName,
                   t.status as status, t.costCny as costCny
            from NoteReferenceEntity r, InferenceTaskEntity t
            where r.id in :referenceIds and t.id = r.referenceId
              and t.requestedBy.id = r.note.owner.id
              and t.inputFile.owner.id = r.note.owner.id
              and r.referenceType = com.robustvision.platform.domain.NoteReferenceType.TASK
            """)
    List<TaskTargetSummary> findTaskTargets(@Param("referenceIds") Collection<Long> referenceIds);

    interface EntryTargetSummary {
        Long getReferenceRowId();
        String getTitle();
        String getDomain();
        String getTopicName();
    }

    @Query("""
            select r.id as referenceRowId, e.title as title,
                   e.topic.domain as domain, e.topic.name as topicName
            from NoteReferenceEntity r, KnowledgeEntryEntity e
            where r.id in :referenceIds and e.id = r.referenceId
              and (e.owner is null or e.owner.id = r.note.owner.id)
              and (e.topic.owner is null or e.topic.owner.id = r.note.owner.id)
              and r.referenceType = com.robustvision.platform.domain.NoteReferenceType.ENTRY
            """)
    List<EntryTargetSummary> findEntryTargets(@Param("referenceIds") Collection<Long> referenceIds);
}
