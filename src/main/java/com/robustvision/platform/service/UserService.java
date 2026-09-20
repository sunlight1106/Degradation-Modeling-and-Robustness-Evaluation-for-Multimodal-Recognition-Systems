package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.RoleEntity;
import com.robustvision.platform.domain.UserEntity;
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

    public UserService(UserRepository userRepository, RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder, CurrentUserService currentUserService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUserService = currentUserService;
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
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase();
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
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "邮箱已注册");
        }
        String password = request.password();
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_WEAK", "密码至少需要同时包含字母和数字");
        }
        RoleEntity role = roleRepository.findByCode("RESEARCHER")
                .orElseThrow(() -> new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DEFAULT_ROLE_MISSING", "默认用户角色尚未初始化"));
        UserEntity user = new UserEntity(username, passwordEncoder.encode(password), username, email, role);
        return toView(userRepository.save(user));
    }

    @Transactional
    public ApiDtos.UserView update(Long id, ApiDtos.UpdateUserRequest request) {
        UserEntity user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        UserEntity operator = currentUserService.requireCurrent();
        if (operator.getId().equals(id) && request.status() != null && request.status() != user.getStatus()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SELF_STATUS_CHANGE", "不能修改自己的启用状态");
        }
        if (request.displayName() != null) user.setDisplayName(request.displayName().trim());
        if (request.email() != null) {
            String email = request.email().trim().toLowerCase();
            if (userRepository.existsByEmailAndIdNot(email, id)) {
                throw new BusinessException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "邮箱已存在");
            }
            user.setEmail(email);
        }
        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        if (request.status() != null) {
            protectLastAdmin(user, request.status(), request.roleId());
            user.setStatus(request.status());
        }
        if (request.roleId() != null) {
            RoleEntity nextRole = requireRole(request.roleId());
            if (operator.getId().equals(id) && !nextRole.getCode().equals(user.getRole().getCode())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "SELF_ROLE_CHANGE", "不能修改自己的角色");
            }
            protectLastAdmin(user, request.status(), nextRole.getId());
            user.setRole(nextRole);
        }
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
        RoleEntity role = requireRole(roleId);
        if ("ADMIN".equals(role.getCode())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ADMIN_ROLE_LOCKED", "管理员角色固定拥有全部权限");
        }
        Set<String> unknown = new LinkedHashSet<>(requestedPermissions);
        unknown.removeAll(Permissions.allCodes());
        if (!unknown.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "UNKNOWN_PERMISSION", "包含未知权限: " + unknown);
        }
        role.setPermissions(requestedPermissions);
        return toRoleView(roleRepository.save(role));
    }

    @Transactional
    public ApiDtos.RoleView createRole(ApiDtos.CreateRoleRequest request) {
        currentUserService.requireSuperAdmin();
        String code = request.code().trim().toUpperCase();
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
                user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(), user.getStatus(),
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

    private void protectLastAdmin(UserEntity user, com.robustvision.platform.domain.UserStatus requestedStatus, Long requestedRoleId) {
        if (!"ADMIN".equals(user.getRole().getCode())) return;
        boolean disabling = requestedStatus == com.robustvision.platform.domain.UserStatus.DISABLED;
        boolean demoting = requestedRoleId != null && !requestedRoleId.equals(user.getRole().getId());
        if ((disabling || demoting) && userRepository.countByRoleCodeAndStatus("ADMIN", com.robustvision.platform.domain.UserStatus.ACTIVE) <= 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "LAST_ADMIN_REQUIRED", "必须至少保留一个启用的最高管理员");
        }
    }
}
