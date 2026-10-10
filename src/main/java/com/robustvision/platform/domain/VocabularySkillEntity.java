package com.robustvision.platform.domain;
import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="vocabulary_skill",uniqueConstraints=@UniqueConstraint(columnNames={"owner_id","term_key","skill_key"}))
public class VocabularySkillEntity {
 @Id @Column(length=36) private String id;
 @Column(name="owner_id",nullable=false) private Long ownerId;
 @Column(name="term_key",nullable=false,length=100) private String termKey;
 @Column(name="skill_key",nullable=false,length=64) @JdbcTypeCode(SqlTypes.CHAR) private String skillKey;
 @Column(nullable=false,length=16) private String kind;
 @Column(nullable=false,length=500) private String detail;
 @Column(name="correct_count",nullable=false) private int correctCount;
 @Column(name="wrong_count",nullable=false) private int wrongCount;
 @Column(name="independent_count",nullable=false) private int independentCount;
 @Column(name="last_attempt",nullable=false) private Instant lastAttempt;
}
