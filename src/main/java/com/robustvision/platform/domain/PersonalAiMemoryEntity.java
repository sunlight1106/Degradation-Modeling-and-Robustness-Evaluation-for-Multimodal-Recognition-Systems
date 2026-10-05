package com.robustvision.platform.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="personal_ai_memory")
public class PersonalAiMemoryEntity {
    @Id @Column(length=36) public String id=UUID.randomUUID().toString();
    @Column(name="owner_id",nullable=false) public Long ownerId;
    @Column(nullable=false,length=100) public String title;
    @Column(nullable=false,length=1000) public String body;
    @Column(nullable=false) public boolean enabled=true;
    @Version public long revision;
    @Column(name="created_at",nullable=false) public Instant createdAt=Instant.now();
    @Column(name="updated_at",nullable=false) public Instant updatedAt=Instant.now();
}
