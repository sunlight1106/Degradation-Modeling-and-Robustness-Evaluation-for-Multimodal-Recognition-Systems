package com.robustvision.platform.repository;

import com.robustvision.platform.domain.UserSessionEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserSessionRepository extends JpaRepository<UserSessionEntity, String> {
    @Query("select s from UserSessionEntity s join fetch s.user u where s.id = :id and u.username = :username and s.revokedAt is null and s.expiresAt > :now")
    Optional<UserSessionEntity> findActive(@Param("id") String id, @Param("username") String username, @Param("now") Instant now);
    List<UserSessionEntity> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(Long userId, Instant now);
    boolean existsByIdAndUserId(String id, Long userId);
    @Modifying
    @Query("update UserSessionEntity s set s.revokedAt = :now where s.user.id = :userId and s.revokedAt is null")
    int revokeAll(@Param("userId") Long userId, @Param("now") Instant now);
    @Modifying
    @Query("update UserSessionEntity s set s.revokedAt = :now where s.user.id = :userId and s.id <> :keepId and s.revokedAt is null")
    int revokeOthers(@Param("userId") Long userId, @Param("keepId") String keepId, @Param("now") Instant now);
    @Modifying
    @Query("update UserSessionEntity s set s.revokedAt = :now where s.id = :id and s.user.id = :userId and s.revokedAt is null")
    int revokeOwned(@Param("id") String id, @Param("userId") Long userId, @Param("now") Instant now);
    @Modifying
    @Query("update UserSessionEntity s set s.lastSeenAt = :now where s.id = :id and s.revokedAt is null and s.lastSeenAt < :before")
    int touch(@Param("id") String id, @Param("now") Instant now, @Param("before") Instant before);
    @Modifying
    @Query("update UserSessionEntity s set s.revokedAt = :now where s.user.id in (select u.id from UserEntity u where u.role.id = :roleId) and s.revokedAt is null")
    int revokeByRole(@Param("roleId") Long roleId, @Param("now") Instant now);
    @Modifying
    @Query("delete from UserSessionEntity s where s.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
