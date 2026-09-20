package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.NoteEntity;
import com.robustvision.platform.domain.NoteShareEntity;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.NoteShareRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

/**
 * 笔记分享服务。
 *
 * 分享范围限定为「平台内已登录用户可只读查看」，不生成免登录公开链接：
 * 笔记可能包含个人研究记录，公开链接一旦外泄无法收回，登录门槛是必要的边界。
 * token 使用 SecureRandom 生成 24 字节随机值（Base64url 后 32 字符），不可枚举。
 */
@Service
public class NoteShareService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int MAX_EXPIRY_DAYS = 365;

    private final NoteShareRepository shareRepository;
    private final NoteService noteService;
    private final NoteReferenceService referenceService;
    private final CurrentUserService currentUserService;
    private final String publicWebUrl;

    public NoteShareService(NoteShareRepository shareRepository,
                            NoteService noteService,
                            NoteReferenceService referenceService,
                            CurrentUserService currentUserService,
                            @Value("${app.payment.public-web-url:http://localhost:4173}") String publicWebUrl) {
        this.shareRepository = shareRepository;
        this.noteService = noteService;
        this.referenceService = referenceService;
        this.currentUserService = currentUserService;
        this.publicWebUrl = publicWebUrl;
    }

    @Transactional
    public ApiDtos.NoteShareView create(String noteId, ApiDtos.CreateNoteShareRequest request) {
        UserEntity user = currentUser();
        NoteEntity note = noteService.requireOwnEntity(noteId);

        Instant expiresAt = null;
        if (request != null && request.expiresInDays() != null) {
            int days = request.expiresInDays();
            if (days <= 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "SHARE_EXPIRY_INVALID", "有效期必须为正整数天数");
            }
            if (days > MAX_EXPIRY_DAYS) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "SHARE_EXPIRY_TOO_LONG",
                        "有效期最长 " + MAX_EXPIRY_DAYS + " 天");
            }
            expiresAt = Instant.now().plus(days, ChronoUnit.DAYS);
        }

        String token = generateToken();
        NoteShareEntity share = shareRepository.save(new NoteShareEntity(
                note, user, token,
                request == null ? null : trimToNull(request.label()),
                expiresAt));
        return toView(share);
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.NoteShareView> list(String noteId) {
        currentUser();
        noteService.requireOwnEntity(noteId);
        return shareRepository.findByNoteIdOrderByCreatedAtDesc(noteId).stream()
                .map(this::toView).toList();
    }

    @Transactional
    public void revoke(String noteId, String shareId) {
        UserEntity user = currentUser();
        noteService.requireOwnEntity(noteId);
        NoteShareEntity share = shareRepository.findById(shareId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "SHARE_NOT_FOUND", "分享链接不存在"));
        if (!share.getNote().getId().equals(noteId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "SHARE_FORBIDDEN", "无权操作该分享链接");
        }
        if (share.getRevokedAt() == null) share.revoke(Instant.now());
        shareRepository.save(share);
    }

    /**
     * 通过 token 读取分享笔记。调用方（Controller）需保证请求已登录，
     * 这里只校验 token 有效性与时效，不再做归属校验——分享的意义就是跨用户只读。
     */
    @Transactional
    public ApiDtos.SharedNoteView readByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SHARE_TOKEN_MISSING", "缺少分享令牌");
        }
        NoteShareEntity share = shareRepository.findByToken(token.trim())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "SHARE_NOT_FOUND",
                        "分享链接不存在或已被撤销"));
        if (!share.isAccessible(Instant.now())) {
            throw new BusinessException(HttpStatus.GONE, "SHARE_EXPIRED", "该分享链接已过期或被撤销");
        }
        share.incrementViews();
        shareRepository.save(share);

        NoteEntity note = share.getNote();
        return new ApiDtos.SharedNoteView(
                note.getTitle(), note.getBody(),
                KnowledgeService.splitTags(note.getTags()),
                note.getOwner().getDisplayName(),
                note.getCreatedAt(), note.getUpdatedAt(),
                referenceService.resolveForNote(note.getId()));
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private UserEntity currentUser() {
        return currentUserService.requireCurrent();
    }

    private ApiDtos.NoteShareView toView(NoteShareEntity share) {
        return new ApiDtos.NoteShareView(
                share.getId(), share.getToken(), share.getLabel(),
                buildShareUrl(share.getToken()),
                share.isAccessible(Instant.now()), share.getViewCount(),
                share.getExpiresAt(), share.getCreatedAt());
    }

    private String buildShareUrl(String token) {
        String base = publicWebUrl == null || publicWebUrl.isBlank() ? "http://localhost:4173" : publicWebUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/shared/" + token;
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
