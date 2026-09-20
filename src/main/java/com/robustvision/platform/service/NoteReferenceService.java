package com.robustvision.platform.service;

import com.robustvision.platform.domain.FileAssetEntity;
import com.robustvision.platform.domain.InferenceTaskEntity;
import com.robustvision.platform.domain.KnowledgeEntryEntity;
import com.robustvision.platform.domain.NoteReferenceEntity;
import com.robustvision.platform.domain.NoteReferenceType;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.FileAssetRepository;
import com.robustvision.platform.repository.InferenceTaskRepository;
import com.robustvision.platform.repository.KnowledgeEntryRepository;
import com.robustvision.platform.repository.NoteReferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 笔记引用的解析服务：把 FILE / TASK / ENTRY 三类多态引用统一转成可展示信息。
 *
 * 因跨表多态没有数据库外键，这里负责校验目标是否存在，并把已删除的目标标记为
 * accessible=false，而不是抛异常——这样旧笔记不会因为引用对象被删而无法打开。
 */
@Service
public class NoteReferenceService {

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
        return referenceRepository.findByNoteIdOrderBySortOrderAscIdAsc(noteId).stream()
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public boolean exists(String noteId, NoteReferenceType type, String referenceId) {
        return referenceRepository.existsByNoteIdAndReferenceTypeAndReferenceId(noteId, type, referenceId);
    }

    /** 校验引用目标当前是否存在，供笔记服务在新增引用时调用。 */
    @Transactional(readOnly = true)
    public boolean targetExists(NoteReferenceType type, String referenceId) {
        return switch (type) {
            case FILE -> fileAssetRepository.existsById(referenceId);
            case TASK -> inferenceTaskRepository.existsById(referenceId);
            case ENTRY -> knowledgeEntryRepository.existsById(referenceId);
        };
    }

    @Transactional(readOnly = true)
    public List<NoteReferenceEntity> findReferencingNotes(NoteReferenceType type, String referenceId) {
        return referenceRepository.findByReferenceTypeAndReferenceId(type, referenceId);
    }

    private ApiDtos.NoteReferenceView toView(NoteReferenceEntity reference) {
        NoteReferenceType type = reference.getReferenceType();
        String label = reference.getLabel();
        String displayTitle;
        String displayMeta;
        boolean accessible;

        switch (type) {
            case FILE -> {
                Optional<FileAssetEntity> file = fileAssetRepository.findById(reference.getReferenceId());
                accessible = file.isPresent();
                FileAssetEntity entity = file.orElse(null);
                displayTitle = label != null && !label.isBlank()
                        ? label
                        : (entity != null ? entity.getOriginalName() : "已删除的文件");
                displayMeta = entity != null
                        ? entity.getContentType() + " · " + formatSize(entity.getSizeBytes()) + " · SHA-256 " + shortHash(entity.getSha256())
                        : "引用目标已被删除";
            }
            case TASK -> {
                Optional<InferenceTaskEntity> task = inferenceTaskRepository.findById(reference.getReferenceId());
                accessible = task.isPresent();
                InferenceTaskEntity entity = task.orElse(null);
                displayTitle = label != null && !label.isBlank()
                        ? label
                        : (entity != null ? "推理任务 " + entity.getTraceId() : "已删除的推理任务");
                displayMeta = entity != null
                        ? entity.getModel().getName() + " · " + entity.getStatus()
                            + (entity.getCostCny() != null ? " · ¥" + entity.getCostCny() : "")
                            + " · traceId " + entity.getTraceId()
                        : "引用目标已被删除";
            }
            case ENTRY -> {
                Optional<KnowledgeEntryEntity> entry = knowledgeEntryRepository.findById(reference.getReferenceId());
                accessible = entry.isPresent();
                KnowledgeEntryEntity entity = entry.orElse(null);
                displayTitle = label != null && !label.isBlank()
                        ? label
                        : (entity != null ? entity.getTitle() : "已删除的知识卡");
                displayMeta = entity != null
                        ? entity.getTopic().getDomain() + " · " + entity.getTopic().getName()
                        : "引用目标已被删除";
            }
            default -> {
                accessible = false;
                displayTitle = label != null ? label : "未知引用";
                displayMeta = "不支持的引用类型";
            }
        }

        return new ApiDtos.NoteReferenceView(
                reference.getId(), type.name(), reference.getReferenceId(), label,
                displayTitle, displayMeta, accessible);
    }

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
