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
    private final com.robustvision.platform.repository.AdminAuditRepository audit;

    public UserService(UserRepository userRepository, RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder, CurrentUserService currentUserService, UserSessionService sessions, EntityManager entityManager,
                       com.robustvision.platform.repository.AdminAuditRepository audit) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUserService = currentUserService;
        this.sessions = sessions;
        this.entityManager = entityManager;
        this.audit = audit;
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
        UserEntity operator = requireAdminForMutation();
        AccountService.validateNewPassword(request.password());
        String username = request.username().trim();
        if(username.matches("(?i)PKB-[A-F0-9]{32}"))throw new BusinessException(HttpStatus.BAD_REQUEST,"USERNAME_RESERVED","此格式保留给系统身份码，请换一个用户名");
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
        userRepository.save(user);
        record(operator, "USER_CREATE", "USER", user.getId(), "分配角色：" + role.getCode());
        return toView(user);
    }

    @Transactional
    public ApiDtos.UserView register(ApiDtos.RegisterRequest request) {
        String username = request.username().trim();
        if(username.matches("(?i)PKB-[A-F0-9]{32}"))throw new BusinessException(HttpStatus.BAD_REQUEST,"USERNAME_RESERVED","此格式保留给系统身份码，请换一个用户名");
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
        if(request.displayName()!=null&&!request.displayName().isBlank())user.setDisplayName(request.displayName().trim());
        if(request.discoverable()!=null)user.setDiscoverable(request.discoverable());
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
        List<String> changes = new java.util.ArrayList<>();
        if (operator.getId().equals(id) && request.status() != null && request.status() != user.getStatus()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SELF_STATUS_CHANGE", "不能修改自己的启用状态");
        }
        if (request.displayName() != null) {
            changes.add("显示名称已更新");
            if (request.displayName().isBlank()) throw new BusinessException(HttpStatus.BAD_REQUEST, "DISPLAY_NAME_REQUIRED", "显示名称不能为空");
            user.setDisplayName(request.displayName().trim());
        }
        if (request.email() != null) {
            changes.add("邮箱已更新");
            String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
            if (userRepository.existsByEmailAndIdNot(email, id)) {
                throw new BusinessException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "邮箱已存在");
            }
            securityChanged |= !email.equals(user.getEmail());
            user.setEmail(email);
        }
        if (request.password() != null && !request.password().isBlank()) {
            changes.add("密码已重置（不记录密码内容）");
            AccountService.validateNewPassword(request.password());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            securityChanged = true;
        }
        if (request.status() != null) {
            changes.add("状态：" + user.getStatus() + " → " + request.status());
            protectLastAdmin(user, request.status(), request.roleId());
            securityChanged |= request.status() != user.getStatus();
            user.setStatus(request.status());
        }
        if (request.roleId() != null) {
            RoleEntity nextRole = requireRole(request.roleId());
            changes.add("角色：" + user.getRole().getCode() + " → " + nextRole.getCode());
            if (operator.getId().equals(id) && !nextRole.getCode().equals(user.getRole().getCode())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "SELF_ROLE_CHANGE", "不能修改自己的角色");
            }
            protectLastAdmin(user, request.status(), nextRole.getId());
            securityChanged |= !nextRole.getId().equals(user.getRole().getId());
            user.setRole(nextRole);
            if ("ADMIN".equals(nextRole.getCode())) {
                user.setPermissionOverrides(java.util.Map.of());
                user.setAccessExpiresAt(null);
            }
        }
        if (securityChanged) sessions.revokeAll(user.getId());
        if (!changes.isEmpty()) record(operator, "USER_UPDATE", "USER", id, String.join("；", changes));
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
        UserEntity operator = requireAdminForMutation();
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
        record(operator, "ROLE_PERMISSIONS", "ROLE", roleId, "修改前：" + sorted(role.getPermissions()) + "；修改后：" + sorted(requestedPermissions));
        role.setPermissions(requestedPermissions);
        return toRoleView(roleRepository.save(role));
    }

    @Transactional
    public ApiDtos.RoleView createRole(ApiDtos.CreateRoleRequest request) {
        UserEntity operator = requireAdminForMutation();
        String code = request.code().trim().toUpperCase(java.util.Locale.ROOT);
        if (roleRepository.existsByCode(code)) {
            throw new BusinessException(HttpStatus.CONFLICT, "ROLE_CODE_EXISTS", "角色编码已存在");
        }
        Set<String> requested = request.permissions() == null ? Set.of() : request.permissions();
        validatePermissions(requested);
        RoleEntity role = new RoleEntity(code, request.name().trim(),
                request.description() == null ? "" : request.description().trim(), requested);
        roleRepository.save(role);
        record(operator, "ROLE_CREATE", "ROLE", role.getId(), "角色：" + code + "；权限：" + sorted(requested));
        return toRoleView(role);
    }

    public ApiDtos.UserView toView(UserEntity user) {
        return new ApiDtos.UserView(
                user.getId(), user.getIdentityCode(), user.getUsername(), user.getDisplayName(), user.getEmail(), user.getStatus(),
                user.getRole().getId(), user.getRole().getCode(), user.getRole().getName(),
                Permissions.effective(user), user.getCreatedAt(), user.getAccessExpiresAt()
        );
    }

    private ApiDtos.RoleView toRoleView(RoleEntity role) {
        return new ApiDtos.RoleView(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                "ADMIN".equals(role.getCode()) ? Permissions.allCodes() : new LinkedHashSet<>(role.getPermissions()), role.getCreatedAt());
    }

    @Transactional
    public com.robustvision.platform.dto.AdminDtos.Access updateAccess(Long id, com.robustvision.platform.dto.AdminDtos.AccessRequest request) {
        UserEntity operator = requireAdminForMutation();
        UserEntity target = userRepository.findLockedById(id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        entityManager.refresh(target, LockModeType.PESSIMISTIC_WRITE);
        if (currentUserService.isSuperAdmin(target)) throw new BusinessException(HttpStatus.BAD_REQUEST, "ADMIN_ACCESS_LOCKED", "管理员始终拥有全部权限，不能设置单独权限或到期时间");
        validatePermissions(request.grants()); validatePermissions(request.denies());
        if (request.expiresAt() != null && (request.expiresAt().isBefore(java.time.Instant.parse("1970-01-01T00:00:01Z"))
                || request.expiresAt().isAfter(java.time.Instant.parse("2038-01-19T03:14:07Z")))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ACCESS_EXPIRY", "有效期超出可保存范围；长期使用可不设到期时间");
        }
        Set<String> overlap = new LinkedHashSet<>(request.grants()); overlap.retainAll(request.denies());
        if (!overlap.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST, "CONFLICTING_PERMISSIONS", "同一权限不能同时允许和禁止");
        var before = access(target);
        java.util.Map<String, Boolean> overrides = new java.util.LinkedHashMap<>();
        request.grants().forEach(code -> overrides.put(code, true)); request.denies().forEach(code -> overrides.put(code, false));
        target.setPermissionOverrides(overrides); target.setAccessExpiresAt(request.expiresAt());
        sessions.revokeAll(id);
        record(operator, "USER_ACCESS", "USER", id, "原授权：" + sorted(before.grants()) + "；原禁用：" + sorted(before.denies())
                + "；新授权：" + sorted(request.grants()) + "；新禁用：" + sorted(request.denies())
                + "；到期：" + before.expiresAt() + " → " + request.expiresAt());
        return access(userRepository.save(target));
    }

    @Transactional
    public void revokeUserSessions(Long id) {
        UserEntity operator = requireAdminForMutation();
        UserEntity target = userRepository.findLockedById(id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        if (operator.getId().equals(id)) throw new BusinessException(HttpStatus.BAD_REQUEST, "SELF_SESSION_REVOKE", "请在个人设置中管理自己的登录设备");
        sessions.revokeAll(target.getId());
        record(operator, "USER_SESSIONS", "USER", id, "已撤销全部登录会话");
    }

    public com.robustvision.platform.dto.AdminDtos.Access access(UserEntity user) {
        Set<String> grants = new LinkedHashSet<>(), denies = new LinkedHashSet<>();
        user.getPermissionOverrides().forEach((code, allowed) -> { if (Boolean.TRUE.equals(allowed)) grants.add(code); else denies.add(code); });
        return new com.robustvision.platform.dto.AdminDtos.Access("ADMIN".equals(user.getRole().getCode()) ? Permissions.allCodes() : new LinkedHashSet<>(user.getRole().getPermissions()),
                grants, denies, Permissions.effective(user), user.getAccessExpiresAt());
    }

    private String sorted(Set<String> codes) { return codes.stream().sorted().collect(java.util.stream.Collectors.joining(", ")); }
    private void record(UserEntity operator, String action, String type, Long id, String detail) {
        audit.save(new com.robustvision.platform.domain.AdminAuditEntity(operator, action, type, id, detail));
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
        if (!operator.hasActiveAccess(java.time.Instant.now())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账户已停用，请重新登录");
        }
        return operator;
    }

    private void protectLastAdmin(UserEntity user, com.robustvision.platform.domain.UserStatus requestedStatus, Long requestedRoleId) {
        if (!"ADMIN".equals(user.getRole().getCode()) || user.getStatus() != UserStatus.ACTIVE) return;
        boolean disabling = requestedStatus == com.robustvision.platform.domain.UserStatus.DISABLED;
        boolean demoting = requestedRoleId != null && !requestedRoleId.equals(user.getRole().getId());
        var available=userRepository.findLockedActiveAdminIds(user.getRole().getId());
        if ((disabling || demoting) && available.contains(user.getId()) && available.size() <= 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "LAST_ADMIN_REQUIRED", "必须至少保留一个启用的最高管理员");
        }
    }
}
