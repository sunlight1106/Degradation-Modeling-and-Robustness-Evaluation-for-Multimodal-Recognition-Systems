package com.robustvision.platform.repository;

import com.robustvision.platform.domain.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, String> {
    List<MessageEntity> findBySenderIdOrderByCreatedAtDesc(Long senderId);
}
