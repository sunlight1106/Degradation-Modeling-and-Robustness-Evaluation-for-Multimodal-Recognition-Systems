package com.robustvision.platform.repository;

import com.robustvision.platform.domain.NoteEntity;
import com.robustvision.platform.domain.NoteStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;
import java.util.Optional;

public interface NoteRepository extends JpaRepository<NoteEntity, String> {

    List<NoteEntity> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId);

    List<NoteEntity> findByOwnerIdAndStatusOrderByUpdatedAtDesc(Long ownerId, NoteStatus status);

    Optional<NoteEntity> findByIdAndOwnerId(String id, Long ownerId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from NoteEntity n where n.id = :id and n.owner.id = :ownerId")
    Optional<NoteEntity> lockOwned(@Param("id") String id, @Param("ownerId") Long ownerId);

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

    interface ListRow {
        String getId(); String getTitle(); String getPreviewBody(); String getTags();
        NoteStatus getStatus(); java.time.Instant getCreatedAt(); java.time.Instant getUpdatedAt();
        String getLibrary(); String getContentFormat(); String getParentId(); long getRevision();
    }

    @Query("""
        select n.id as id, n.title as title, substring(n.body,1,2048) as previewBody,
        n.tags as tags, n.status as status, n.createdAt as createdAt, n.updatedAt as updatedAt,
        n.library as library, n.contentFormat as contentFormat, n.parentId as parentId, n.revision as revision
        from NoteEntity n where n.owner.id=:ownerId and (:status is null or n.status=:status)
        and (:keyword='' or lower(n.title) like lower(concat('%',:keyword,'%'))
        or lower(n.body) like lower(concat('%',:keyword,'%')) or lower(n.tags) like lower(concat('%',:keyword,'%')))
        order by n.updatedAt desc,n.id
        """)
    List<ListRow> summaries(@Param("ownerId") Long ownerId,@Param("status") NoteStatus status,@Param("keyword") String keyword);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from NoteEntity n where n.owner.id=:ownerId and n.id in :ids order by n.id")
    List<NoteEntity> lockBatch(@Param("ownerId") Long ownerId,@Param("ids") Collection<String> ids);

    interface ShareCount {
        String getNoteId();
        long getShareCount();
    }

    @Query("select s.note.id as noteId, count(s) as shareCount from NoteShareEntity s where s.note.id in :noteIds group by s.note.id")
    List<ShareCount> countSharesByNoteIds(@Param("noteIds") Collection<String> noteIds);

    @org.springframework.data.jpa.repository.Modifying
    @Query("update NoteEntity n set n.parentId = null where n.parentId = :id and n.owner.id = :ownerId")
    void detachChildren(@Param("id") String id, @Param("ownerId") Long ownerId);

    long countByOwnerId(Long ownerId);
}
