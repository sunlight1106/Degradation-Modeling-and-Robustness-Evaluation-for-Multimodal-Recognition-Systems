package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "chat_message", uniqueConstraints = @UniqueConstraint(columnNames = {"sender_id", "client_id"}))
public class ChatMessageEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "contact_id") private ContactLinkEntity contact;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "sender_id") private UserEntity sender;
    @Column(name = "client_id", nullable = false, length = 36) private String clientId;
    @Column(nullable = false, columnDefinition = "TEXT") private String body;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    protected ChatMessageEntity() {}
    public ChatMessageEntity(ContactLinkEntity contact, UserEntity sender, String clientId, String body) {
        this.contact = contact; this.sender = sender; this.clientId = clientId; this.body = body;
    }
    public Long getId() { return id; }
    public ContactLinkEntity getContact() { return contact; }
    public UserEntity getSender() { return sender; }
    public String getClientId() { return clientId; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
