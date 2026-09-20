package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "message_recipient", uniqueConstraints = @UniqueConstraint(name = "uk_message_recipient", columnNames = {"message_id", "recipient_id"}))
public class MessageRecipientEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private MessageEntity message;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private UserEntity recipient;
    @Column(name = "read_at")
    private Instant readAt;

    protected MessageRecipientEntity() {}
    public MessageRecipientEntity(MessageEntity message, UserEntity recipient) { this.message = message; this.recipient = recipient; }
    public Long getId() { return id; }
    public MessageEntity getMessage() { return message; }
    public UserEntity getRecipient() { return recipient; }
    public Instant getReadAt() { return readAt; }
    public void markRead() { if (readAt == null) readAt = Instant.now(); }
}
