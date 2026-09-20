package com.robustvision.platform.repository;

import com.robustvision.platform.domain.NoteShareEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NoteShareRepository extends JpaRepository<NoteShareEntity, String> {

    Optional<NoteShareEntity> findByToken(String token);

    List<NoteShareEntity> findByNoteIdOrderByCreatedAtDesc(String noteId);
}
