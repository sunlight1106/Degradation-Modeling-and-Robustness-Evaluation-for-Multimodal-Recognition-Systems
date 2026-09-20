package com.robustvision.platform.repository;

import com.robustvision.platform.domain.NoteReferenceEntity;
import com.robustvision.platform.domain.NoteReferenceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoteReferenceRepository extends JpaRepository<NoteReferenceEntity, Long> {

    List<NoteReferenceEntity> findByNoteIdOrderBySortOrderAscIdAsc(String noteId);

    void deleteByNoteId(String noteId);

    /** 反查引用了某目标的笔记引用记录，用于双向链接。 */
    List<NoteReferenceEntity> findByReferenceTypeAndReferenceId(NoteReferenceType referenceType, String referenceId);

    boolean existsByNoteIdAndReferenceTypeAndReferenceId(String noteId, NoteReferenceType referenceType, String referenceId);
}
