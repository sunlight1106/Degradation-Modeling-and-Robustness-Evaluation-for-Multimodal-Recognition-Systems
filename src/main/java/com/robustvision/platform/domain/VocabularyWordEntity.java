package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name="vocabulary_word")
public class VocabularyWordEntity {
    @Id @Column(length=36) public String id;
    @Column(name="book_id",nullable=false,length=36) public String bookId;
    @Column(nullable=false,length=80) public String term;
    @Column(nullable=false,length=120) public String ipa;
    @Column(nullable=false,length=24) public String pos;
    @Column(nullable=false,length=160) public String meaning;
    @Column(name="example_text",nullable=false,length=400) public String exampleText;
    @Column(name="example_translation",nullable=false,length=400) public String exampleTranslation;
    @Column(nullable=false,length=1500) public String distractors;
    @Column(name="sort_order",nullable=false) public int sortOrder;
}
