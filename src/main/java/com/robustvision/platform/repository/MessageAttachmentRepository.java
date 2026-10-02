package com.robustvision.platform.repository;

import com.robustvision.platform.domain.MessageAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Collection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface MessageAttachmentRepository extends JpaRepository<MessageAttachmentEntity, Long> {
    List<MessageAttachmentEntity> findByMessageIdOrderByIdAsc(String messageId);
    interface AttachmentRow {
        Long getId();
        String getMessageId();
        String getFileName();
        String getContentType();
        long getSizeBytes();
    }

    @Query("""
            select a.id as id, a.message.id as messageId, a.file.originalName as fileName,
                   a.file.contentType as contentType, a.file.sizeBytes as sizeBytes
            from MessageAttachmentEntity a where a.message.id in :messageIds order by a.id asc
            """)
    List<AttachmentRow> findRowsByMessageIds(@Param("messageIds") Collection<String> messageIds);

    Optional<MessageAttachmentEntity> findByIdAndMessageId(Long id, String messageId);
}
