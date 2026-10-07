package com.robustvision.platform.repository;
import com.robustvision.platform.domain.NoteVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface NoteVersionRepository extends JpaRepository<NoteVersionEntity,String> {
    boolean existsByNoteIdAndRevision(String noteId,long revision);
    List<NoteVersionEntity> findByOwnerIdAndNoteIdOrderByRevisionDesc(Long owner,String note,Pageable page);
    Optional<NoteVersionEntity> findByIdAndOwnerIdAndNoteId(String id,Long owner,String note);
    void deleteByNoteIdAndOwnerId(String note,Long owner);
}
