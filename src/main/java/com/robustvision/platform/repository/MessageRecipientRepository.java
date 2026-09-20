package com.robustvision.platform.repository;

import com.robustvision.platform.domain.MessageRecipientEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MessageRecipientRepository extends JpaRepository<MessageRecipientEntity, Long> {
    List<MessageRecipientEntity> findByRecipientIdOrderByMessageCreatedAtDesc(Long recipientId);
    List<MessageRecipientEntity> findByMessageIdOrderByIdAsc(String messageId);
    Optional<MessageRecipientEntity> findByMessageIdAndRecipientId(String messageId, Long recipientId);
}
