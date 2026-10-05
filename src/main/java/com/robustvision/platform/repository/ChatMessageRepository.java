package com.robustvision.platform.repository;

import com.robustvision.platform.domain.ChatMessageEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.util.*;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
    Optional<ChatMessageEntity> findBySenderIdAndClientId(Long senderId, String clientId);
    @EntityGraph(attributePaths = "sender")
    List<ChatMessageEntity> findByContactIdAndIdGreaterThanOrderByIdAsc(Long contactId, Long after, Pageable page);
    @EntityGraph(attributePaths = "sender")
    List<ChatMessageEntity> findByContactIdAndIdLessThanOrderByIdDesc(Long contactId, Long before, Pageable page);
}
