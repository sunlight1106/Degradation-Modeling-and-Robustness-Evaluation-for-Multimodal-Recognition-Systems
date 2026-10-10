package com.robustvision.platform.domain;
import jakarta.persistence.*;
@Entity @Table(name="note_reminder")
public class NoteReminderEntity {
 @Id @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR) @Column(name="note_id",length=36,columnDefinition="CHAR(36)") private String noteId;
 @Column(name="owner_id",nullable=false) private Long ownerId;
 @Column(name="due_date",nullable=false) private java.time.LocalDate dueDate;
 @Column(name="repeat_days",nullable=false) private int repeatDays;
}
