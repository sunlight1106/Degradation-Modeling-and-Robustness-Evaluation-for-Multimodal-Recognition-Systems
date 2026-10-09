package com.robustvision.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Metadata only: bookmarked source content is rechecked when it is read. */
@Entity @Table(name="workspace_shortcut",uniqueConstraints=@UniqueConstraint(name="uq_shortcut_resource",columnNames={"owner_id","kind","resource_key"}))
public class WorkspaceShortcutEntity {
    @Id @Column(length=36) public String id;
    @Column(name="owner_id",nullable=false) public Long ownerId;
    @Column(nullable=false,length=12) public String kind;
    @Column(nullable=false,length=180) public String title;
    @Column(name="resource_key",nullable=false,length=64) public String resourceKey;
    @Column(name="source_kind",length=16) public String sourceKind;
    @Column(name="source_id",length=36) public String sourceId;
    @Column(name="filters_json",columnDefinition="LONGTEXT") public String filtersJson;
    @Column(name="created_at",nullable=false) public Instant createdAt;
}
