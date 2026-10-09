package com.robustvision.platform.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="workspace_preference",uniqueConstraints=@UniqueConstraint(columnNames={"workspace_id","owner_id"}))
public class WorkspacePreferenceEntity {
    @Id @Column(length=36) public String id;
    @Column(name="workspace_id",nullable=false) public Long workspaceId;
    @Column(name="owner_id",nullable=false) public Long ownerId;
    @Column(nullable=false) public boolean pinned;
    @Column(nullable=false) public boolean muted;
    @Column(name="read_through") public Instant readThrough;
    @Column(name="read_message_id",nullable=false,length=36) public String readMessageId;
}
