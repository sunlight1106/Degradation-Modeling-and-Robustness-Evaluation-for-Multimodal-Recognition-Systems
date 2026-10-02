package com.robustvision.platform.repository;

import com.robustvision.platform.domain.MessageRecipientEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Collection;
import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface MessageRecipientRepository extends JpaRepository<MessageRecipientEntity, Long> {
    List<MessageRecipientEntity> findByRecipientIdOrderByMessageCreatedAtDesc(Long recipientId);
    List<MessageRecipientEntity> findByMessageIdOrderByIdAsc(String messageId);
    interface RecipientRow {
        String getMessageId();
        Long getRecipientId();
        String getUsername();
        String getDisplayName();
        String getEmail();
        Instant getReadAt();
    }

    @Query("""
            select r.message.id as messageId, r.recipient.id as recipientId,
                   r.recipient.username as username, r.recipient.displayName as displayName,
                   r.recipient.email as email, r.readAt as readAt
            from MessageRecipientEntity r where r.message.id in :messageIds order by r.id asc
            """)
    List<RecipientRow> findRowsByMessageIds(@Param("messageIds") Collection<String> messageIds);

    Optional<MessageRecipientEntity> findByMessageIdAndRecipientId(String messageId, Long recipientId);
}
