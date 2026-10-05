package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.NoteEntity;
import com.robustvision.platform.domain.NoteReferenceEntity;
import com.robustvision.platform.domain.NoteReferenceType;
import com.robustvision.platform.domain.NoteStatus;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.NoteReferenceRepository;
import com.robustvision.platform.repository.NoteRepository;
import com.robustvision.platform.repository.NoteShareRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;

/**
 * 笔记服务：Markdown 笔记的增删改查、引用管理与搜索。
 *
 * 笔记一律归属当前登录用户，任何读写都先校验归属，不跨用户访问。
 */
@Service
public class NoteService {

    private static final int EXCERPT_LENGTH = 160;

    private final com.robustvision.platform.repository.UserRepository users;
    private final NoteRepository noteRepository;
    private final NoteReferenceRepository referenceRepository;
    private final NoteShareRepository shareRepository;
    private final NoteReferenceService referenceService;
    private final CurrentUserService currentUserService;

    public NoteService(NoteRepository noteRepository,
                       NoteReferenceRepository referenceRepository,
                       NoteShareRepository shareRepository,
                       NoteReferenceService referenceService,
                       CurrentUserService currentUserService, com.robustvision.platform.repository.UserRepository users) {
        this.users = users;
        this.noteRepository = noteRepository;
        this.referenceRepository = referenceRepository;
        this.shareRepository = shareRepository;
        this.referenceService = referenceService;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.NoteSummaryView> list(String status, String keyword) {
        UserEntity user = currentUserService.requireCurrent();
        List<NoteEntity> notes;
        if (keyword != null && !keyword.isBlank()) {
            notes = noteRepository.search(user.getId(), keyword.trim());
            if (status != null && !status.isBlank()) {
                NoteStatus filter = parseStatus(status);
                notes = notes.stream().filter(n -> n.getStatus() == filter).toList();
            }
        } else if (status != null && !status.isBlank()) {
            notes = noteRepository.findByOwnerIdAndStatusOrderByUpdatedAtDesc(user.getId(), parseStatus(status));
        } else {
            notes = noteRepository.findByOwnerIdOrderByUpdatedAtDesc(user.getId());
        }
        Map<String, Integer> shareCounts = new HashMap<>();
        // Keep IN clauses bounded and count shares without materializing share entities.
        for (int start = 0; start < notes.size(); start += 500) {
            List<String> ids = notes.subList(start, Math.min(start + 500, notes.size())).stream().map(NoteEntity::getId).toList();
            for (NoteRepository.ShareCount row : noteRepository.countSharesByNoteIds(ids)) {
                shareCounts.put(row.getNoteId(), Math.toIntExact(row.getShareCount()));
            }
        }
        return notes.stream().map(note -> toSummaryView(note, shareCounts.getOrDefault(note.getId(), 0))).toList();
    }

    @Transactional(readOnly = true)
    public ApiDtos.NoteView get(String id) {
        UserEntity user = currentUserService.requireCurrent();
        return toView(requireOwn(id, user));
    }

    @Transactional
    public ApiDtos.NoteView create(ApiDtos.CreateNoteRequest request) {
        UserEntity user = currentUserService.requireCurrent();
        users.lockNoteOwner(user.getId());
        NoteEntity note = noteRepository.save(new NoteEntity(
                user, request.title().trim(), request.body(),
                trimToNull(request.tags()), parseStatusOrDefault(request.status())));
        assignParent(note, request.parentId(), user);
        note.setLibrary(normalizeLibrary(request.library()));
        if (request.contentFormat() != null) note.setContentFormat(request.contentFormat());
        return toView(note);
    }

    @Transactional
    public ApiDtos.NoteView update(String id, ApiDtos.UpdateNoteRequest request) {
        UserEntity user = currentUserService.requireCurrent();
        if (request.parentId() != null) users.lockNoteOwner(user.getId());
        NoteEntity note = requireOwn(id, user);
        if (request.parentId() != null) assignParent(note, request.parentId(), user);
        if (request.title() != null && !request.title().isBlank()) note.setTitle(request.title().trim());
        if (request.body() != null) note.setBody(request.body());
        if (request.library() != null) note.setLibrary(normalizeLibrary(request.library()));
        if (request.contentFormat() != null) note.setContentFormat(request.contentFormat());
        if (request.tags() != null) note.setTags(trimToNull(request.tags()));
        if (request.status() != null && !request.status().isBlank()) note.setStatus(parseStatus(request.status()));
        note.setUpdatedAt(Instant.now());
        return toView(noteRepository.save(note));
    }

    @Transactional
    public void delete(String id) {
        UserEntity user = currentUserService.requireCurrent();
        NoteEntity note = requireOwn(id, user);
        users.lockNoteOwner(user.getId());
        // 引用与分享随 note 级联删除（数据库 ON DELETE CASCADE）
        noteRepository.detachChildren(id, user.getId());
        noteRepository.delete(note);
    }

    // ------------------------------------------------------------------
    // 引用
    // ------------------------------------------------------------------

    @Transactional
    public ApiDtos.NoteView addReference(String id, ApiDtos.AddNoteReferenceRequest request) {
        UserEntity user = currentUserService.requireCurrent();
        NoteEntity note = requireOwn(id, user);
        NoteReferenceType type = parseReferenceType(request.referenceType());
        if (!referenceService.targetAccessible(type, request.referenceId(), user.getId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "REFERENCE_TARGET_NOT_FOUND",
                    "引用目标不存在或无权访问");
        }
        if (referenceService.exists(note.getId(), type, request.referenceId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "REFERENCE_DUPLICATE", "该引用已存在");
        }
        int order = referenceRepository.findByNoteIdOrderBySortOrderAscIdAsc(note.getId()).size() * 10;
        referenceRepository.save(new NoteReferenceEntity(
                note, type, request.referenceId(), trimToNull(request.label()), order));
        return toView(note);
    }

    @Transactional
    public ApiDtos.NoteView removeReference(String noteId, Long referenceId) {
        UserEntity user = currentUserService.requireCurrent();
        NoteEntity note = requireOwn(noteId, user);
        NoteReferenceEntity reference = referenceRepository.findById(referenceId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFERENCE_NOT_FOUND", "引用不存在"));
        if (!reference.getNote().getId().equals(note.getId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "REFERENCE_FORBIDDEN", "无权操作该引用");
        }
        referenceRepository.delete(reference);
        return toView(note);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 供导出与分享服务复用的原始实体读取（已校验归属）。 */
    @Transactional(readOnly = true)
    public NoteEntity requireOwnEntity(String id) {
        return requireOwn(id, currentUserService.requireCurrent());
    }

    private NoteEntity requireOwn(String id, UserEntity user) {
        return noteRepository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "NOTE_NOT_FOUND", "笔记不存在或无权访问"));
    }

    private ApiDtos.NoteSummaryView toSummaryView(NoteEntity note, int shareCount) {
        return new ApiDtos.NoteSummaryView(
                note.getId(), note.getTitle(), excerpt("HTML".equals(note.getContentFormat()) ? NoteContent.htmlToMarkdown(note.getBody()) : note.getBody()),
                KnowledgeService.splitTags(note.getTags()),
                statusView(note.getStatus()),
                shareCount,
                note.getCreatedAt(), note.getUpdatedAt(), note.getLibrary(), note.getContentFormat(), note.getParentId());
    }

    private ApiDtos.NoteView toView(NoteEntity note) {
        return new ApiDtos.NoteView(
                note.getId(), note.getTitle(), note.getBody(),
                KnowledgeService.splitTags(note.getTags()),
                statusView(note.getStatus()),
                referenceService.resolveForNote(note.getId()),
                shareRepository.findByNoteIdOrderByCreatedAtDesc(note.getId()).size(),
                note.getCreatedAt(), note.getUpdatedAt(), note.getLibrary(), note.getContentFormat(), note.getParentId());
    }

    /** 去掉 Markdown 语法标记后取前若干字符作为列表摘要。 */
    private String excerpt(String body) {
        if (body == null || body.isBlank()) return "";
        String plain = body.replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("`[^`]*`", " ")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("^#{1,6}\\s*", "")
                .replaceAll("[*_>~|-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return plain.length() <= EXCERPT_LENGTH ? plain : plain.substring(0, EXCERPT_LENGTH) + "…";
    }

    private ApiDtos.NoteStatusView statusView(NoteStatus status) {
        return switch (status) {
            case DRAFT -> new ApiDtos.NoteStatusView("DRAFT", "草稿");
            case ACTIVE -> new ApiDtos.NoteStatusView("ACTIVE", "已定稿");
            case ARCHIVED -> new ApiDtos.NoteStatusView("ARCHIVED", "已归档");
        };
    }

    static NoteStatus parseStatus(String value) {
        try {
            return NoteStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTE_STATUS_INVALID",
                    "笔记状态只能是 DRAFT、ACTIVE 或 ARCHIVED");
        }
    }

    private NoteStatus parseStatusOrDefault(String value) {
        if (value == null || value.isBlank()) return NoteStatus.DRAFT;
        return parseStatus(value);
    }

    static NoteReferenceType parseReferenceType(String value) {
        try {
            return NoteReferenceType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REFERENCE_TYPE_INVALID",
                    "引用类型只能是 FILE、TASK 或 ENTRY");
        }
    }

    private void assignParent(NoteEntity note, String requested, UserEntity user) {
        String id = requested == null || requested.isBlank() ? null : requested.trim();
        String next = id;
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (next != null) {
            if (next.equals(note.getId()) || !visited.add(next) || visited.size() > 50)
                throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTE_HIERARCHY_INVALID", "不能把页面移入自身或其子页面，或选择过深的父级链路");
            next = noteRepository.lockOwned(next, user.getId()).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "NOTE_NOT_FOUND", "父页面不存在或无权访问")).getParentId();
        }
        note.setParentId(id);
    }

    private String normalizeLibrary(String value) {
        return value == null || value.isBlank() ? "综合学习" : value.trim();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
