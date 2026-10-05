package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.AccountDtos;
import com.robustvision.platform.repository.UserSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class UserSessionService {
    public record SessionDetails(String sessionId) {}
    private final UserSessionRepository sessions;
    public UserSessionService(UserSessionRepository sessions) { this.sessions = sessions; }

    @Transactional
    public UserSessionEntity create(UserEntity user, Instant createdAt, Instant expiresAt, String userAgent) {
        // Expired session metadata has no auth value and need not accumulate forever.
        sessions.deleteExpiredBefore(createdAt.minus(30, ChronoUnit.DAYS));
        String safeAgent = userAgent == null ? null : userAgent.replaceAll("[\\p{Cntrl}]", " ");
        if (safeAgent != null && safeAgent.length() > 400) safeAgent = safeAgent.substring(0, 400);
        return sessions.save(new UserSessionEntity(user, createdAt, expiresAt, safeAgent));
    }

    @Transactional
    public boolean isActive(String sessionId, String username) {
        Instant now = Instant.now();
        var session = sessions.findActive(sessionId, username, now);
        if (session.isEmpty() || session.get().getUser().getStatus() != UserStatus.ACTIVE) return false;
        sessions.touch(sessionId, now, now.minus(5, ChronoUnit.MINUTES));
        return true;
    }

    public String currentSessionId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof SessionDetails details) {
            return details.sessionId();
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "请重新登录");
    }

    @Transactional(readOnly = true)
    public List<AccountDtos.SessionView> list(Long userId) {
        String currentId = currentSessionId();
        return sessions.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(userId, Instant.now())
                .stream().map(s -> new AccountDtos.SessionView(s.getId(), s.getCreatedAt(), s.getExpiresAt(),
                        s.getId().equals(currentId), s.getUserAgent(), s.getLastSeenAt())).toList();
    }

    @Transactional
    public void revokeOwned(Long userId, String sessionId) {
        if (!sessions.existsByIdAndUserId(sessionId, userId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "会话不存在");
        }
        sessions.revokeOwned(sessionId, userId, Instant.now());
    }
    @Transactional
    public void revokeOthers(Long userId) { sessions.revokeOthers(userId, currentSessionId(), Instant.now()); }
    @Transactional
    public void revokeByRole(Long roleId) { sessions.revokeByRole(roleId, Instant.now()); }
    @Transactional
    public void revokeAll(Long userId) { sessions.revokeAll(userId, Instant.now()); }
}
