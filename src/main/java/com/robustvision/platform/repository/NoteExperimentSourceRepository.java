package com.robustvision.platform.repository;
import com.robustvision.platform.domain.InferenceTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
public interface NoteExperimentSourceRepository extends JpaRepository<InferenceTaskEntity, String> {
    @Query("""
            select t from InferenceTaskEntity t join fetch t.inputFile join fetch t.model
            where t.requestedBy.id = :ownerId and t.inputFile.owner.id = :ownerId
              and t.status = com.robustvision.platform.domain.InferenceStatus.COMPLETED
            order by t.createdAt desc, t.id desc
            """)
    List<InferenceTaskEntity> findNoteSources(@Param("ownerId") Long ownerId, org.springframework.data.domain.Pageable pageable);

    @Query("""
            select t from InferenceTaskEntity t join fetch t.inputFile join fetch t.model
            where t.id = :id and t.requestedBy.id = :ownerId and t.inputFile.owner.id = :ownerId
            """)
    java.util.Optional<InferenceTaskEntity> findOwnNoteSource(@Param("id") String id, @Param("ownerId") Long ownerId);

}
