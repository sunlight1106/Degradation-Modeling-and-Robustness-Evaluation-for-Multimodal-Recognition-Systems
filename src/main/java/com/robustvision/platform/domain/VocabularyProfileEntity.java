package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name="vocabulary_profile")
public class VocabularyProfileEntity {
    @Id @Column(name="owner_id") public Long ownerId;
    @Column(name="zone_id",length=80) public String zoneId;
    @Column(name="daily_goal",nullable=false) public int dailyGoal=10;
    @Column(name="selected_book_id",length=36) public String selectedBookId;
    @Column(name="updated_at",nullable=false) public Instant updatedAt;
}
