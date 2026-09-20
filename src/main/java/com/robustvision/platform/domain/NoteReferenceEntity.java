package com.robustvision.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 笔记的多态引用：把笔记与文件资产、推理任务或知识卡关联起来。
 * reference_id 指向 file_asset / inference_task / knowledge_entry 的 CHAR(36) 主键。
 * 因跨表多态无法建立数据库外键，目标存在性由服务层校验。
 */
@Entity
@Table(name = "note_reference")
public class NoteReferenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "note_id", nullable = false)
    private NoteEntity note;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 20)
    private NoteReferenceType referenceType;

    @Column(name = "reference_id", nullable = false, length = 36)
    private String referenceId;

    @Column(length = 180)
    private String label;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected NoteReferenceEntity() {}

    public NoteReferenceEntity(NoteEntity note, NoteReferenceType referenceType,
                               String referenceId, String label, int sortOrder) {
        this.note = note;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.label = label;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public NoteEntity getNote() { return note; }
    public NoteReferenceType getReferenceType() { return referenceType; }
    public String getReferenceId() { return referenceId; }
    public String getLabel() { return label; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }

    public void setLabel(String label) { this.label = label; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
