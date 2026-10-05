package com.robustvision.platform.repository;

import com.robustvision.platform.domain.UserEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    @EntityGraph(attributePaths = {"role", "role.permissions"})
    Optional<UserEntity> findByUsername(String username);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserEntity user where user.id = :id")
    Optional<UserEntity> findLockedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserEntity user where user.username = :username")
    Optional<UserEntity> findLockedByUsername(@Param("username") String username);

    boolean existsByUsername(String username);
    @Query("select u.id from UserEntity u where u.username = :username")
    Optional<Long> findIdByUsername(@Param("username") String username);
    boolean existsByEmail(String email);
    boolean existsByEmailAndIdNot(String email, Long id);
    Optional<UserEntity> findByEmail(String email);
    List<UserEntity> findAllByOrderByCreatedAtDesc();

    @Query("""
            select u.id as id, u.identityCode as identityCode, u.username as username, u.displayName as displayName, u.role.code as roleCode
            from UserEntity u where u.status = com.robustvision.platform.domain.UserStatus.ACTIVE
            and u.id <> :currentId and (:allTargets = true or u.id in :targetIds)
            and not exists (select c.id from ContactLinkEntity c where ((c.lowUserId = :currentId and c.highUserId = u.id) or (c.highUserId = :currentId and c.lowUserId = u.id)) and (c.lowBlocked = true or c.highBlocked = true))
            and (:admin = true or u.role.code = 'ADMIN' or exists (select c.id from ContactLinkEntity c where c.status = 'ACCEPTED' and ((c.lowUserId = :currentId and c.highUserId = u.id) or (c.highUserId = :currentId and c.lowUserId = u.id))) or exists (
                select mine.id from WorkspaceMemberEntity mine, WorkspaceMemberEntity other
                where mine.workspace.id = other.workspace.id and mine.user.id = :currentId and other.user.id = u.id
                and mine.role <> com.robustvision.platform.domain.WorkspaceMemberRole.VIEWER
                and 'CONTENT_READ' member of mine.permissions and 'CONTENT_WRITE' member of mine.permissions
                and 'CONTENT_READ' member of other.permissions))
            order by u.displayName, u.id
            """)
    List<MessageContactRow> findMessageContacts(@Param("currentId") Long currentId, @Param("admin") boolean admin,
            @Param("allTargets") boolean allTargets, @Param("targetIds") java.util.Collection<Long> targetIds);

    @Query("""
        select u.id as id, u.identityCode as identityCode, u.username as username, u.displayName as displayName
        from UserEntity u where u.id <> :me and u.discoverable = true
        and u.status = com.robustvision.platform.domain.UserStatus.ACTIVE
        and (u.identityCode = :identityCode or lower(u.username) like :query escape '!' or lower(u.displayName) like :query escape '!')
        and not exists (select c.id from ContactLinkEntity c where ((c.lowUserId = :me and c.highUserId = u.id) or (c.highUserId = :me and c.lowUserId = u.id)) and (c.lowBlocked = true or c.highBlocked = true))
        order by u.username, u.id
        """)
    List<MessageContactRow> searchPeople(@Param("me") long me, @Param("query") String query, @Param("identityCode") String identityCode, org.springframework.data.domain.Pageable pageable);

    interface MessageContactRow {
        Long getId();
        String getIdentityCode();
        String getUsername();
        String getDisplayName();
        String getRoleCode();
    }
    /** A locking/current read, including on MySQL REPEATABLE READ transactions. */
    @Query(value = "SELECT id FROM app_user WHERE role_id = :roleId AND status = 'ACTIVE' ORDER BY id FOR UPDATE", nativeQuery = true)
    List<Long> findLockedActiveAdminIds(@Param("roleId") Long roleId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> lockNoteOwner(@Param("id") Long id);

    long countByRoleCodeAndStatus(String roleCode, com.robustvision.platform.domain.UserStatus status);
}
