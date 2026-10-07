package com.robustvision.platform.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="note_version", uniqueConstraints=@UniqueConstraint(columnNames={"note_id","revision"}))
public class NoteVersionEntity {
    @Id @Column(length=36) public String id = UUID.randomUUID().toString();
    @Column(name="note_id",nullable=false,length=36) @JdbcTypeCode(SqlTypes.CHAR) public String noteId;
    @Column(name="owner_id",nullable=false) public Long ownerId;
    @Column(nullable=false) public long revision;
    @Column(nullable=false,length=180) public String title;
    @Column(nullable=false,columnDefinition="LONGTEXT") public String body;
    @Column(length=500) public String tags;
    @Column(nullable=false,length=40) public String library;
    @Column(name="content_format",nullable=false,length=12) public String contentFormat;
    @Column(nullable=false,length=20) public String status;
    @Column(name="created_at",nullable=false) public Instant createdAt = Instant.now();
    public NoteVersionEntity() {}
    public NoteVersionEntity(NoteEntity n) {
        noteId=n.getId(); ownerId=n.getOwner().getId(); revision=n.getRevision(); title=n.getTitle();
        body=n.getBody(); tags=n.getTags(); library=n.getLibrary(); contentFormat=n.getContentFormat(); status=n.getStatus().name();
    }
}
