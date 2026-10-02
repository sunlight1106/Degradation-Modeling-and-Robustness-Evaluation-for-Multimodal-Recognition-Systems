package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name="vocabulary_book")
public class VocabularyBookEntity {
    @Id @Column(length=36) public String id;
    @Column(name="owner_id") public Long ownerId;
    @Column(nullable=false,length=100) public String title;
    @Column(nullable=false,length=600) public String description;
    @Column(nullable=false,length=300) public String attribution;
    @Column(nullable=false,length=40) public String level;
    @Column(name="created_at",nullable=false) public Instant createdAt;
}
