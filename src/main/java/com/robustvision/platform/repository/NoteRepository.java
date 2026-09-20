package com.robustvision.platform.repository;

import com.robustvision.platform.domain.NoteEntity;
import com.robustvision.platform.domain.NoteStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NoteRepository extends JpaRepository<NoteEntity, String> {

    List<NoteEntity> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId);

    List<NoteEntity> findByOwnerIdAndStatusOrderByUpdatedAtDesc(Long ownerId, NoteStatus status);

    Optional<NoteEntity> findByIdAndOwnerId(String id, Long ownerId);

    /** 按标题、正文或标签模糊搜索当前用户的笔记。 */
    @Query("""
            SELECT n FROM NoteEntity n
            WHERE n.owner.id = :ownerId
              AND (LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(n.body) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(n.tags) LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY n.updatedAt DESC
            """)
    List<NoteEntity> search(@Param("ownerId") Long ownerId, @Param("keyword") String keyword);

    long countByOwnerId(Long ownerId);
}
