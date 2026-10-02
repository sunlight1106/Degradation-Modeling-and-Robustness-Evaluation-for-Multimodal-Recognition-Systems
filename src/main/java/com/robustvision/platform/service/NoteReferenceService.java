package com.robustvision.platform.service;

import com.robustvision.platform.domain.NoteReferenceEntity;
import com.robustvision.platform.domain.NoteReferenceType;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.FileAssetRepository;
import com.robustvision.platform.repository.InferenceTaskRepository;
import com.robustvision.platform.repository.KnowledgeEntryRepository;
import com.robustvision.platform.repository.NoteReferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 笔记引用的解析服务：把 FILE / TASK / ENTRY 三类多态引用统一转成可展示信息。
 *
 * 因跨表多态没有数据库外键，这里负责校验目标是否存在，并把已删除的目标标记为
 * accessible=false，而不是抛异常——这样旧笔记不会因为引用对象被删而无法打开。
 */
@Service
public class NoteReferenceService {

    private static final int TARGET_BATCH_SIZE = 500;

    private final NoteReferenceRepository referenceRepository;
    private final FileAssetRepository fileAssetRepository;
    private final InferenceTaskRepository inferenceTaskRepository;
    private final KnowledgeEntryRepository knowledgeEntryRepository;

    public NoteReferenceService(NoteReferenceRepository referenceRepository,
                                FileAssetRepository fileAssetRepository,
                                InferenceTaskRepository inferenceTaskRepository,
                                KnowledgeEntryRepository knowledgeEntryRepository) {
        this.referenceRepository = referenceRepository;
        this.fileAssetRepository = fileAssetRepository;
        this.inferenceTaskRepository = inferenceTaskRepository;
        this.knowledgeEntryRepository = knowledgeEntryRepository;
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.NoteReferenceView> resolveForNote(String noteId) {
        List<NoteReferenceRepository.ReferenceSummary> references = referenceRepository.findSummariesByNoteId(noteId);
        // One summary query plus one target query per nonempty type/batch. Never
        // hydrate result JSON, knowledge bodies, owners or roles for display labels.
        Map<Long, ResolvedTarget> targets = new HashMap<>();
        for (NoteReferenceType type : NoteReferenceType.values()) {
            List<Long> ids = references.stream().filter(reference -> reference.getReferenceType() == type)
                    .map(NoteReferenceRepository.ReferenceSummary::getId).toList();
            for (int start = 0; start < ids.size(); start += TARGET_BATCH_SIZE) {
                resolveTargets(type, ids.subList(start, Math.min(start + TARGET_BATCH_SIZE, ids.size())), targets);
            }
        }
        return references.stream().map(reference -> toView(reference, targets.get(reference.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public boolean exists(String noteId, NoteReferenceType type, String referenceId) {
        return referenceRepository.existsByNoteIdAndReferenceTypeAndReferenceId(noteId, type, referenceId);
    }

    /** 校验引用目标当前是否存在，供笔记服务在新增引用时调用。 */
    @Transactional(readOnly = true)
    public boolean targetAccessible(NoteReferenceType type, String referenceId, Long ownerId) {
        return switch (type) {
            case FILE -> fileAssetRepository.existsByIdAndOwnerId(referenceId, ownerId);
            case TASK -> inferenceTaskRepository.existsByIdAndRequestedByIdAndInputFileOwnerId(referenceId, ownerId, ownerId);
            case ENTRY -> knowledgeEntryRepository.isReadable(referenceId, ownerId);
        };
    }

    @Transactional(readOnly = true)
    public List<NoteReferenceEntity> findReferencingNotes(NoteReferenceType type, String referenceId) {
        return referenceRepository.findByReferenceTypeAndReferenceId(type, referenceId);
    }

    private void resolveTargets(NoteReferenceType type, List<Long> ids, Map<Long, ResolvedTarget> targets) {
        switch (type) {
            case FILE -> referenceRepository.findFileTargets(ids).forEach(file -> targets.put(file.getReferenceRowId(),
                    new ResolvedTarget(file.getOriginalName(), file.getContentType() + " · " + formatSize(file.getSizeBytes())
                            + " · SHA-256 " + shortHash(file.getSha256()))));
            case TASK -> referenceRepository.findTaskTargets(ids).forEach(task -> targets.put(task.getReferenceRowId(),
                    new ResolvedTarget("推理任务 " + task.getTraceId(), task.getModelName() + " · " + task.getStatus()
                            + (task.getCostCny() != null ? " · ¥" + task.getCostCny() : "")
                            + " · traceId " + task.getTraceId())));
            case ENTRY -> referenceRepository.findEntryTargets(ids).forEach(entry -> targets.put(entry.getReferenceRowId(),
                    new ResolvedTarget(entry.getTitle(), entry.getDomain() + " · " + entry.getTopicName())));
        }
    }

    private ApiDtos.NoteReferenceView toView(NoteReferenceRepository.ReferenceSummary reference, ResolvedTarget target) {
        NoteReferenceType type = reference.getReferenceType();
        String label = reference.getLabel();
        String fallbackTitle = switch (type) {
            case FILE -> "已删除的文件";
            case TASK -> "已删除的推理任务";
            case ENTRY -> "已删除的知识卡";
        };
        String displayTitle = label != null && !label.isBlank() ? label : target != null ? target.title() : fallbackTitle;
        // Resolve only targets the note owner may reference. Authorized note sharing grants
        // this embedded metadata, never access to the underlying private file or task.
        return new ApiDtos.NoteReferenceView(reference.getId(), type.name(), reference.getReferenceId(), label,
                displayTitle, target != null ? target.meta() : "引用目标已被删除", target != null);
    }

    private record ResolvedTarget(String title, String meta) {}

    private String shortHash(String sha256) {
        if (sha256 == null || sha256.length() < 12) return String.valueOf(sha256);
        return sha256.substring(0, 12) + "…";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
