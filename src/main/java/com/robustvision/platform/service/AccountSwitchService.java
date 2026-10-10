package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.UserStatus;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.UserRepository;
import com.robustvision.platform.repository.UserSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.stream.Stream;

@Service
public class AccountSwitchService {
    private final CurrentUserService current;
    private final UserRepository users;
    private final UserSessionRepository sessionRows;
    private final UserSessionService sessions;
    private final AuthService auth;
    private final EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Autowired private LoginIdentityService identities;
    public AccountSwitchService(CurrentUserService current, UserRepository users, UserSessionRepository sessionRows,
                                UserSessionService sessions, AuthService auth, EntityManager entityManager) {
        this.current = current; this.users = users; this.sessionRows = sessionRows;
        this.sessions = sessions; this.auth = auth; this.entityManager = entityManager;
    }

    @Transactional(noRollbackFor=BusinessException.class)
    public ApiDtos.LoginResponse switchAccount(ApiDtos.LoginRequest request, String userAgent) {
        var source = current.requireCurrent();
        String sessionId = sessions.currentSessionId();
        Long targetId = users.findIdByUsername(identities.username(request.username())).orElseThrow(this::invalidTarget);
        if (source.getId().equals(targetId))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ACCOUNT_ALREADY_CURRENT", "这个账号已经登录，请选择另一个账号");
        // Use the same stable user-lock order as other account mutations.
        Stream.of(source.getId(), targetId).sorted().forEach(id -> users.findLockedById(id).orElseThrow(this::invalidTarget));
        entityManager.refresh(source, LockModeType.PESSIMISTIC_WRITE);
        if (!source.hasActiveAccess(Instant.now()) || sessionRows.findActive(sessionId, source.getUsername(), Instant.now()).isEmpty())
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "当前会话已失效，请重新登录");
        try {
            // Verify password and second factor before revoking the source session.
            // Failed second-factor counters commit without logging out this account.
            var response=auth.login(request,userAgent);
            if(sessionRows.revokeActive(sessionId,source.getId(),Instant.now())!=1)
                throw new IllegalStateException("Source session changed during account switch");
            return response;
        } catch (BusinessException failure) {
            // A bad target password must not cause the browser to discard its still-valid source session.
            if ("LOGIN_FAILED".equals(failure.getCode())) throw invalidTarget();
            throw failure;
        }
    }
    private BusinessException invalidTarget() {
        return new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_SWITCH_FAILED", "目标账号或密码不正确，或账号不可用。当前登录未改变。");
    }
}
