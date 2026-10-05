package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.RoleEntity;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.domain.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.security.core.context.SecurityContextHolder;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.RoleRepository;
import com.robustvision.platform.repository.UserRepository;
import com.robustvision.platform.security.Permissions;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;
    private final UserSessionService sessions;
    private final EntityManager entityManager;

    public UserService(UserRepository userRepository, RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder, CurrentUserService currentUserService, UserSessionService sessions, EntityManager entityManager) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUserService = currentUserService;
        this.sessions = sessions;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.UserView> listUsers() {
        return userRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ApiDtos.UserView currentUser() {
        return toView(currentUserService.requireCurrent());
    }

    @Transactional
    public ApiDtos.UserView create(ApiDtos.CreateUserRequest request) {
        requireAdminForMutation();
        AccountService.validateNewPassword(request.password());
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "邮箱已存在");
        }
        RoleEntity role = requireRole(request.roleId());
        UserEntity user = new UserEntity(username, passwordEncoder.encode(request.password()),
                request.displayName().trim(), email, role);
        return toView(userRepository.save(user));
    }

    @Transactional
    public ApiDtos.UserView register(ApiDtos.RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "邮箱已注册");
        }
        String password = request.password();
        AccountService.validateNewPassword(password);
        RoleEntity role = roleRepository.findByCode("RESEARCHER")
                .orElseThrow(() -> new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DEFAULT_ROLE_MISSING", "默认用户角色尚未初始化"));
        UserEntity user = new UserEntity(username, passwordEncoder.encode(password), username, email, role);
        return toView(userRepository.save(user));
    }

    @Transactional
    public ApiDtos.UserView update(Long id, ApiDtos.UpdateUserRequest request) {
        UserEntity operator = lockCurrentOperatorForMutation();
        UserEntity user = userRepository.findLockedById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        boolean admin = currentUserService.isSuperAdmin(operator);
        if (!admin && !currentUserService.hasPermission(operator, "user:write")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "没有执行此操作的权限");
        }
        // Delegated user writers may edit display details only. Role assignment,
        // credential reset, email identity and status changes are ADMIN-only.
        if (!admin && ("ADMIN".equals(user.getRole().getCode()) || request.roleId() != null
                || request.status() != null || request.password() != null || request.email() != null)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "只有最高管理员可以修改账户安全设置");
        }
        boolean securityChanged = false;
        if (operator.getId().equals(id) && request.status() != null && request.status() != user.getStatus()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SELF_STATUS_CHANGE", "不能修改自己的启用状态");
        }
        if (request.displayName() != null) {
            if (request.displayName().isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST, "DISPLAY_NAME_REQUIRED", "显示名称不能为空");
            user.setDisplayName(request.displayName().trim());
        }
        if (request.email() != null) {
            String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
            if (userRepository.existsByEmailAndIdNot(email, id)) {
                throw new BusinessException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "邮箱已存在");
            }
            securityChanged |= !email.equals(user.getEmail());
            user.setEmail(email);
        }
        if (request.password() != null && !request.password().isBlank()) {
            AccountService.validateNewPassword(request.password());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            securityChanged = true;
        }
        if (request.status() != null) {
            protectLastAdmin(user, request.status(), request.roleId());
            securityChanged |= request.status() != user.getStatus();
            user.setStatus(request.status());
        }
        if (request.roleId() != null) {
            RoleEntity nextRole = requireRole(request.roleId());
            if (operator.getId().equals(id) && !nextRole.getCode().equals(user.getRole().getCode())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "SELF_ROLE_CHANGE", "不能修改自己的角色");
            }
            protectLastAdmin(user, request.status(), nextRole.getId());
            securityChanged |= !nextRole.getId().equals(user.getRole().getId());
            user.setRole(nextRole);
        }
        if (securityChanged) sessions.revokeAll(user.getId());
        return toView(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.RoleView> listRoles() {
        return roleRepository.findAll().stream().map(this::toRoleView).toList();
    }

    public List<ApiDtos.PermissionView> listPermissions() {
        return Permissions.CATALOG.stream()
                .map(item -> new ApiDtos.PermissionView(item.code(), item.label(), item.group()))
                .toList();
    }

    @Transactional
    public ApiDtos.RoleView updatePermissions(Long roleId, Set<String> requestedPermissions) {
        requireAdminForMutation();
        RoleEntity role = requireRole(roleId);
        if ("ADMIN".equals(role.getCode())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ADMIN_ROLE_LOCKED", "管理员角色固定拥有全部权限");
        }
        Set<String> unknown = new LinkedHashSet<>(requestedPermissions);
        unknown.removeAll(Permissions.allCodes());
        if (!unknown.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "UNKNOWN_PERMISSION", "包含未知权限: " + unknown);
        }
        sessions.revokeByRole(role.getId());
        role.setPermissions(requestedPermissions);
        return toRoleView(roleRepository.save(role));
    }

    @Transactional
    public ApiDtos.RoleView createRole(ApiDtos.CreateRoleRequest request) {
        requireAdminForMutation();
        String code = request.code().trim().toUpperCase(java.util.Locale.ROOT);
        if (roleRepository.existsByCode(code)) {
            throw new BusinessException(HttpStatus.CONFLICT, "ROLE_CODE_EXISTS", "角色编码已存在");
        }
        Set<String> requested = request.permissions() == null ? Set.of() : request.permissions();
        validatePermissions(requested);
        RoleEntity role = new RoleEntity(code, request.name().trim(),
                request.description() == null ? "" : request.description().trim(), requested);
        return toRoleView(roleRepository.save(role));
    }

    public ApiDtos.UserView toView(UserEntity user) {
        return new ApiDtos.UserView(
                user.getId(), user.getIdentityCode(), user.getUsername(), user.getDisplayName(), user.getEmail(), user.getStatus(),
                user.getRole().getId(), user.getRole().getCode(), user.getRole().getName(),
                new LinkedHashSet<>(user.getRole().getPermissions()), user.getCreatedAt()
        );
    }

    private ApiDtos.RoleView toRoleView(RoleEntity role) {
        return new ApiDtos.RoleView(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                new LinkedHashSet<>(role.getPermissions()), role.getCreatedAt());
    }

    private RoleEntity requireRole(Long roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "角色不存在"));
    }

    private void validatePermissions(Set<String> requested) {
        Set<String> unknown = new LinkedHashSet<>(requested);
        unknown.removeAll(Permissions.allCodes());
        if (!unknown.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST, "UNKNOWN_PERMISSION", "包含未知权限: " + unknown);
    }

    private UserEntity requireAdminForMutation() {
        UserEntity operator = lockCurrentOperatorForMutation();
        if (!currentUserService.isSuperAdmin(operator)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "只有最高管理员可以执行此操作");
        }
        return operator;
    }

    /**
     * All administrative writes use one order: ADMIN guard, current operator,
     * then target user/role. Personal account mutations never acquire the guard.
     * Refresh after waiting: previously authenticated/stale entities cannot retain
     * authority after another administrator disables or demotes their account.
     */
    private UserEntity lockCurrentOperatorForMutation() {
        roleRepository.lockAdminGuard().orElseThrow(() -> new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE, "ADMIN_ROLE_MISSING", "管理员角色尚未初始化"));
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录");
        }
        UserEntity operator = userRepository.findLockedByUsername(authentication.getName())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "登录用户已不存在"));
        entityManager.refresh(operator, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(operator.getRole());
        if (operator.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账户已停用，请重新登录");
        }
        return operator;
    }

    private void protectLastAdmin(UserEntity user, com.robustvision.platform.domain.UserStatus requestedStatus, Long requestedRoleId) {
        if (!"ADMIN".equals(user.getRole().getCode()) || user.getStatus() != UserStatus.ACTIVE) return;
        boolean disabling = requestedStatus == com.robustvision.platform.domain.UserStatus.DISABLED;
        boolean demoting = requestedRoleId != null && !requestedRoleId.equals(user.getRole().getId());
        if ((disabling || demoting) && userRepository.findLockedActiveAdminIds(user.getRole().getId()).size() <= 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "LAST_ADMIN_REQUIRED", "必须至少保留一个启用的最高管理员");
        }
    }
}
