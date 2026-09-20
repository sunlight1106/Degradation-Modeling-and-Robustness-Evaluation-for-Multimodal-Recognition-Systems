package com.robustvision.platform.repository;

import com.robustvision.platform.domain.MessageAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MessageAttachmentRepository extends JpaRepository<MessageAttachmentEntity, Long> {
    List<MessageAttachmentEntity> findByMessageIdOrderByIdAsc(String messageId);
    Optional<MessageAttachmentEntity> findByIdAndMessageId(Long id, String messageId);
}
