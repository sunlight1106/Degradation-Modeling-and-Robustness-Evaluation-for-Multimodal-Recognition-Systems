package com.robustvision.platform.repository;

import com.robustvision.platform.domain.WorkspaceMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMemberEntity, Long> {
    List<WorkspaceMemberEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<WorkspaceMemberEntity> findByWorkspaceIdOrderByCreatedAtAsc(Long workspaceId);
    Optional<WorkspaceMemberEntity> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);
}
