package com.robustvision.platform.repository;

import com.robustvision.platform.domain.ContactLinkEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface ContactLinkRepository extends JpaRepository<ContactLinkEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ContactLinkEntity c where c.lowUserId = :low and c.highUserId = :high")
    Optional<ContactLinkEntity> lockPair(@Param("low") long low, @Param("high") long high);

    @Query("select count(c) from ContactLinkEntity c where (c.lowUserId = :user or c.highUserId = :user) and c.status in ('PENDING', 'ACCEPTED')")
    long countActive(@Param("user") long user);

    interface Row {
        Long getId(); Long getUserId(); String getUsername(); String getDisplayName();
        Long getRequesterId(); String getStatus(); boolean getBlockedByMe(); boolean getAvailable();
    }
    @Query("""
        select c.id as id, u.id as userId, u.username as username, u.displayName as displayName,
        c.requesterId as requesterId, c.status as status,
        case when c.lowUserId = :user then c.lowBlocked else c.highBlocked end as blockedByMe,
        case when c.lowBlocked = false and c.highBlocked = false and u.status = com.robustvision.platform.domain.UserStatus.ACTIVE then true else false end as available
        from ContactLinkEntity c, UserEntity u
        where (c.lowUserId = :user or c.highUserId = :user)
        and u.id = case when c.lowUserId = :user then c.highUserId else c.lowUserId end
        and (c.status in ('PENDING', 'ACCEPTED') or (c.lowUserId = :user and c.lowBlocked = true) or (c.highUserId = :user and c.highBlocked = true))
        order by c.updatedAt desc, c.id desc
        """)
    List<Row> visible(@Param("user") long user);
}
