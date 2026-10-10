package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.dto.AccountDtos;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AccountService {
    private final CurrentUserService current;
    private final UserService users;
    private final UserRepository repository;
    private final PasswordEncoder passwords;
    private final UserSessionService sessions;
    private final AccountExportService exports;
    private final EntityManager entityManager;
    public AccountService(CurrentUserService current, UserService users, UserRepository repository,
                          PasswordEncoder passwords, UserSessionService sessions, AccountExportService exports,
                          EntityManager entityManager) {
        this.current = current; this.users = users; this.repository = repository;
        this.passwords = passwords; this.sessions = sessions; this.exports = exports; this.entityManager = entityManager;
    }
    @Transactional(readOnly = true)
    public ApiDtos.UserView profile() { return users.toCurrentView(current.requireCurrent()); }

    @Transactional
    public ApiDtos.UserView updateProfile(AccountDtos.ProfileUpdateRequest request) {
        UserEntity user = currentLocked();
        if (request.email() != null) {
            String email = request.email().trim().toLowerCase(Locale.ROOT);
            if (!email.equals(user.getEmail())) {
                confirmPassword(user, request.currentPassword());
                if (repository.existsByEmailAndIdNot(email, user.getId())) {
                    throw new BusinessException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "邮箱已存在");
                }
                user.setEmail(email);
                sessions.revokeOthers(user.getId());
            }
        }
        if (request.displayName() != null) {
            String displayName = request.displayName().trim();
            if (displayName.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST, "DISPLAY_NAME_REQUIRED", "显示名称不能为空");
            user.setDisplayName(displayName);
        }
        return users.toCurrentView(repository.save(user));
    }
    @Transactional
    public void changePassword(AccountDtos.PasswordChangeRequest request) {
        UserEntity user = currentLocked();
        confirmPassword(user, request.currentPassword());
        validateNewPassword(request.newPassword());
        if (passwords.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "新密码不能与当前密码相同");
        }
        user.setPasswordHash(passwords.encode(request.newPassword()));
        repository.save(user);
        sessions.revokeAll(user.getId());
    }
    @Transactional(readOnly = true)
    public List<AccountDtos.SessionView> listSessions() { return sessions.list(current.requireCurrent().getId()); }
    @Transactional
    public void revokeSession(String id, AccountDtos.PasswordConfirmationRequest request) {
        UserEntity user = currentLocked(); confirmPassword(user, request.currentPassword());
        sessions.revokeOwned(user.getId(), id);
    }
    @Transactional
    public void revokeOthers(AccountDtos.PasswordConfirmationRequest request) {
        UserEntity user = currentLocked(); confirmPassword(user, request.currentPassword());
        sessions.revokeOthers(user.getId());
    }
    @Transactional
    public void logout() { sessions.revokeOwned(current.requireCurrent().getId(), sessions.currentSessionId()); }
    @Transactional(readOnly = true)
    public Map<String, Object> export(AccountDtos.PasswordConfirmationRequest request) {
        UserEntity user = current.requireCurrent(); confirmPassword(user, request.currentPassword());
        return exports.export(user, users.toView(user));
    }
    private UserEntity currentLocked() {
        UserEntity user = current.requireCurrent();
        // Refresh under the lock prevents stale credentials after waiting behind another reset.
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        return user;
    }
    private void confirmPassword(UserEntity user, String value) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(value, user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "CURRENT_PASSWORD_INVALID", "当前密码不正确");
        }
    }
    public static void validateNewPassword(String value) {
        if (value == null || value.length() < 8 || value.getBytes(StandardCharsets.UTF_8).length > 72
                || !value.matches(".*[A-Za-z].*") || !value.matches(".*\\d.*")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_WEAK", "密码需至少 8 位，同时包含字母和数字，且 UTF-8 编码不超过 72 字节");
        }
    }
}
