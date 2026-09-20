package com.robustvision.platform.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "message_attachment", uniqueConstraints = @UniqueConstraint(name = "uk_message_attachment_file", columnNames = {"message_id", "file_id"}))
public class MessageAttachmentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private MessageEntity message;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileAssetEntity file;

    protected MessageAttachmentEntity() {}
    public MessageAttachmentEntity(MessageEntity message, FileAssetEntity file) { this.message = message; this.file = file; }
    public Long getId() { return id; }
    public MessageEntity getMessage() { return message; }
    public FileAssetEntity getFile() { return file; }
}
