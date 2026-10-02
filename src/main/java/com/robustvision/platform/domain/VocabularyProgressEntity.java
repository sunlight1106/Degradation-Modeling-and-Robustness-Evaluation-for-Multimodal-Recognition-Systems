package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name="vocabulary_progress", uniqueConstraints=@UniqueConstraint(name="uq_vocabulary_owner_word",columnNames={"owner_id","word_id"}))
public class VocabularyProgressEntity {
    @Id @Column(length=36) public String id;
    @Column(name="owner_id",nullable=false) public Long ownerId;
    @Column(name="word_id",nullable=false,length=36) public String wordId;
    @Column(name="learning_correct",nullable=false) public int learningCorrect;
    @Column(name="review_stage",nullable=false) public int reviewStage;
    @Column(name="wrong_count",nullable=false) public int wrongCount;
    @Column(nullable=false) public boolean mistake;
    @Column(nullable=false) public boolean starred;
    @Column(name="due_date") public LocalDate dueDate;
    @Column(name="learned_date") public LocalDate learnedDate;
    @Column(name="last_attempt_at") public Instant lastAttemptAt;
    @Column(name="last_review_date") public LocalDate lastReviewDate;
}
