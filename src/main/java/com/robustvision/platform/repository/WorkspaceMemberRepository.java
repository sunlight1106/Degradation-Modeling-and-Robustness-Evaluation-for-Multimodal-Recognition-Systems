package com.robustvision.platform.repository;

import com.robustvision.platform.domain.WorkspaceMemberEntity;
import com.robustvision.platform.domain.WorkspaceMemberRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMemberEntity, Long> {
    /** One row per member permission; the LEFT JOIN also retains members with no permissions. */
    interface MemberDetails {
        Long getId();
        Long getWorkspaceId();
        Long getUserId();
        String getUsername();
        String getDisplayName();
        WorkspaceMemberRole getRole();
        String getPermission();
        Instant getCreatedAt();
    }

    @Query("""
            select m.id as id, m.workspace.id as workspaceId, u.id as userId,
                   u.username as username, u.displayName as displayName,
                   m.role as role, p as permission, m.createdAt as createdAt
            from WorkspaceMemberEntity m join m.user u left join m.permissions p
            where m.workspace.id in :workspaceIds
            order by m.workspace.id, m.createdAt, m.id, p
            """)
    List<MemberDetails> findDetailsByWorkspaceIds(@Param("workspaceIds") Collection<Long> workspaceIds);

    List<WorkspaceMemberEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<WorkspaceMemberEntity> findByWorkspaceIdOrderByCreatedAtAsc(Long workspaceId);
    Optional<WorkspaceMemberEntity> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);
}
