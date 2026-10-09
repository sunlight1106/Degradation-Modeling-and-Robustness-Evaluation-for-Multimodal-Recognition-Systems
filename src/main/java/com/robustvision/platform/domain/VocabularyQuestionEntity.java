package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name="vocabulary_question")
public class VocabularyQuestionEntity {
    @Id @Column(length=36) public String id;
    @Column(name="owner_id",nullable=false) public Long ownerId;
    @Column(name="word_id",nullable=false,length=36) public String wordId;
    @Column(name="book_id",nullable=false,length=36) public String bookId;
    @Column(nullable=false,length=12) public String mode;
    @Column(name="options_json",nullable=false,length=2400) public String optionsJson;
    @Column(name="correct_option_id",nullable=false,length=36) public String correctOptionId;
    @Column(name="created_at",nullable=false) public Instant createdAt;
    @Column(name="expires_at",nullable=false) public Instant expiresAt;
    @Column(name="answered_at") public Instant answeredAt;
    @Column(name="study_date") public LocalDate studyDate;
    @Column(name="answer_correct") public Boolean answerCorrect;
    @Column(name="result_json",columnDefinition="TEXT") public String resultJson;
    @org.hibernate.annotations.ColumnDefault("'CHOICE'") @Column(name="practice_kind",nullable=false,length=16) public String practiceKind="CHOICE";
    @org.hibernate.annotations.ColumnDefault("0") @Column(name="hint_level",nullable=false) public int hintLevel;
    @Column(name="prompt_text",length=400) public String promptText;
    @Column(name="expected_text",length=160) public String expectedText;
}
