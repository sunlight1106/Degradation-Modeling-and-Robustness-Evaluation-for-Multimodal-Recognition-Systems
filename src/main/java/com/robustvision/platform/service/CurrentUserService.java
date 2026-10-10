package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {
    @org.springframework.beans.factory.annotation.Autowired private ModerationGuard moderation;
    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserEntity requireCurrent() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录");
        }
        UserEntity user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "登录用户已不存在"));
        if (!user.hasActiveAccess(java.time.Instant.now())) throw new BusinessException(HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE", "账户已停用或使用期限已到，请联系管理员");
        return user;
    }

    public boolean hasPermission(UserEntity user, String permission) {
        return effectivePermissions(user).contains(permission);
    }

    public java.util.Set<String> effectivePermissions(UserEntity user) {
        return moderation.permissions(user.getId(),com.robustvision.platform.security.Permissions.effective(user));
    }

    public boolean isSuperAdmin(UserEntity user) { return "ADMIN".equals(user.getRole().getCode()); }

    public void requireSuperAdmin() {
        if (!isSuperAdmin(requireCurrent())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "只有最高管理员可以执行此操作");
        }
    }
}
