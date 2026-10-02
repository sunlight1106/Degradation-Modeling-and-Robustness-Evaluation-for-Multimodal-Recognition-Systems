package com.robustvision.platform.repository;

import com.robustvision.platform.domain.WorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, Long> {
    /** Scalar summaries avoid hydrating eager owner roles and their permission collections. */
    interface WorkspaceSummary {
        Long getId();
        String getName();
        String getSlug();
        String getColor();
        Long getOwnerId();
        String getOwnerName();
        Instant getCreatedAt();
        Instant getUpdatedAt();
    }

    @Query("""
            select w.id as id, w.name as name, w.slug as slug, w.color as color,
                   o.id as ownerId, o.displayName as ownerName,
                   w.createdAt as createdAt, w.updatedAt as updatedAt
            from WorkspaceEntity w join w.owner o order by w.id
            """)
    List<WorkspaceSummary> findAllSummaries();

    @Query("""
            select w.id as id, w.name as name, w.slug as slug, w.color as color,
                   o.id as ownerId, o.displayName as ownerName,
                   w.createdAt as createdAt, w.updatedAt as updatedAt
            from WorkspaceMemberEntity m join m.workspace w join w.owner o
            where m.user.id = :userId order by m.createdAt desc, m.id desc
            """)
    List<WorkspaceSummary> findMemberSummaries(@Param("userId") Long userId);

    boolean existsBySlug(String slug);
    Optional<WorkspaceEntity> findBySlug(String slug);
}
