package com.robustvision.platform.repository;

import com.robustvision.platform.domain.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, String> {
    List<MessageEntity> findBySenderIdOrderByCreatedAtDesc(Long senderId);

    // Scalar projections avoid fetching users, roles and permissions for each row.
    interface MessageRow {
        String getId();
        Long getSenderId();
        String getSenderName();
        String getSubject();
        String getBody();
        Instant getCreatedAt();
    }

    @Query("""
            select m.id as id, m.sender.id as senderId, m.sender.displayName as senderName,
                   m.subject as subject, m.body as body, m.createdAt as createdAt
            from MessageEntity m where m.sender.id = :userId
            order by m.createdAt desc, m.id asc
            """)
    List<MessageRow> findSentRows(@Param("userId") Long userId);

    @Query("""
            select m.id as id, m.sender.id as senderId, m.sender.displayName as senderName,
                   m.subject as subject, m.body as body, m.createdAt as createdAt
            from MessageRecipientEntity r join r.message m where r.recipient.id = :userId
            order by m.createdAt desc, m.id asc
            """)
    List<MessageRow> findInboxRows(@Param("userId") Long userId);
}
