package com.robustvision.platform.domain;
import jakarta.persistence.*;
@Entity @Table(name="personal_preference")
public class PersonalPreferenceEntity {
 @Id @Column(name="owner_id") private Long ownerId;
 @Column(nullable=false,columnDefinition="TEXT") private String payload;
 @Column(nullable=false) private long revision;
}
