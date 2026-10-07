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
    List<ChatMessageEntity> findByContactIdAndIdGreaterThanAndIdLessThanOrderByIdDesc(Long contactId, Long clearedThrough, Long before, Pageable page);
    @Query("select m from ChatMessageEntity m join fetch m.sender where m.contact.id=:contact and m.id>:cleared and lower(m.body) like :pattern escape '!' order by m.id desc")
    List<ChatMessageEntity> searchHistory(@org.springframework.data.repository.query.Param("contact") Long contact,@org.springframework.data.repository.query.Param("cleared") long cleared,@org.springframework.data.repository.query.Param("pattern") String pattern,Pageable page);
    boolean existsByIdAndContactId(Long id, Long contactId);
    Optional<ChatMessageEntity> findFirstByContactIdOrderByIdDesc(Long contactId);
}
