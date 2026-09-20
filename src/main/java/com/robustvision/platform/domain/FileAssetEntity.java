package com.robustvision.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "file_asset")
public class FileAssetEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "stored_name", nullable = false)
    private String storedName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "storage_path", nullable = false, length = 600)
    private String storagePath;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserEntity owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FileSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false, length = 20)
    private FileScanStatus scanStatus = FileScanStatus.UNKNOWN;

    @Column(name = "scan_engine", length = 80)
    private String scanEngine;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected FileAssetEntity() {}

    public FileAssetEntity(String originalName, String storedName, String contentType, long sizeBytes,
                           String sha256, String storagePath, UserEntity owner, FileSource source,
                           FileScanStatus scanStatus, String scanEngine) {
        this.originalName = originalName;
        this.storedName = storedName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storagePath = storagePath;
        this.owner = owner;
        this.source = source;
        this.scanStatus = scanStatus;
        this.scanEngine = scanEngine;
        this.scannedAt = Instant.now();
    }

    @PrePersist
    void ensureId() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public String getOriginalName() { return originalName; }
    public String getStoredName() { return storedName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getStoragePath() { return storagePath; }
    public UserEntity getOwner() { return owner; }
    public FileSource getSource() { return source; }
    public FileScanStatus getScanStatus() { return scanStatus; }
    public String getScanEngine() { return scanEngine; }
    public Instant getScannedAt() { return scannedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
