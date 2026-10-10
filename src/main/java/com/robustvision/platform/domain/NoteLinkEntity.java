package com.robustvision.platform.domain;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Entity @Table(name="note_link",uniqueConstraints=@UniqueConstraint(columnNames={"source_id","target_id"}))
public class NoteLinkEntity {
 @Id @Column(length=36) private String id;
 @Column(name="owner_id",nullable=false) private Long ownerId;
 @Column(name="source_id",nullable=false,length=36) @JdbcTypeCode(SqlTypes.CHAR) private String sourceId;
 @Column(name="target_id",nullable=false,length=36) @JdbcTypeCode(SqlTypes.CHAR) private String targetId;
}
