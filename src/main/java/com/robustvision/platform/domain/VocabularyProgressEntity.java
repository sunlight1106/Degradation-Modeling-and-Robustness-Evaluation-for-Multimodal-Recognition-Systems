package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name="vocabulary_progress", uniqueConstraints={@UniqueConstraint(name="uq_vocabulary_owner_word",columnNames={"owner_id","word_id"}), @UniqueConstraint(name="uq_vocabulary_owner_term",columnNames={"owner_id","term_key"})})
public class VocabularyProgressEntity {
    @Id @Column(length=36) public String id;
    @Column(name="owner_id",nullable=false) public Long ownerId;
    @Column(name="word_id",nullable=false,length=36) public String wordId;
    @Column(name="term_key",nullable=false,length=80) public String termKey;
    @Column(name="learning_correct",nullable=false) public int learningCorrect;
    @Column(name="review_stage",nullable=false) public int reviewStage;
    @Column(name="wrong_count",nullable=false) public int wrongCount;
    @Column(nullable=false) public boolean mistake;
    @Column(nullable=false) public boolean starred;
    @Column(name="due_date") public LocalDate dueDate;
    @Column(name="learned_date") public LocalDate learnedDate;
    @Column(name="last_attempt_at") public Instant lastAttemptAt;
    @Column(name="last_review_date") public LocalDate lastReviewDate;
    @org.hibernate.annotations.ColumnDefault("false") @Column(nullable=false) public boolean skipped;
    @org.hibernate.annotations.ColumnDefault("0") @Column(name="independent_correct",nullable=false) public int independentCorrect;
    @org.hibernate.annotations.ColumnDefault("0") @Column(name="prompted_correct",nullable=false) public int promptedCorrect;
    @org.hibernate.annotations.ColumnDefault("0") @Column(name="immediate_correct",nullable=false) public int immediateCorrect;
    @org.hibernate.annotations.ColumnDefault("0") @Column(name="spelling_correct",nullable=false) public int spellingCorrect;
    @org.hibernate.annotations.ColumnDefault("0") @Column(name="collocation_correct",nullable=false) public int collocationCorrect;
    @Column(name="introduced_at") public Instant introducedAt;
    @Column(name="last_viewed_at") public Instant lastViewedAt;
    @Column(name="updated_at") public Instant updatedAt;
}
