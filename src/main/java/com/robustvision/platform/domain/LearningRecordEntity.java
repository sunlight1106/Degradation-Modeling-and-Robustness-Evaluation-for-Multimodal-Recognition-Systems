package com.robustvision.platform.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="learning_record")
public class LearningRecordEntity {
    @Id @Column(length=36) public String id=UUID.randomUUID().toString();
    @Column(name="owner_id",nullable=false) public Long ownerId;
    @Column(nullable=false,length=24) public String kind;
    @Column(nullable=false,length=180) public String title;
    @Column(nullable=false,columnDefinition="LONGTEXT") public String payload;
    @Version @Column(nullable=false) public long revision;
    @Column(name="created_at",nullable=false) public Instant createdAt=Instant.now();
    @Column(name="updated_at",nullable=false) public Instant updatedAt=Instant.now();
    public LearningRecordEntity() {}
    public LearningRecordEntity(long owner,String kind,String title,String payload){ownerId=owner;this.kind=kind;this.title=title;this.payload=payload;}
}
