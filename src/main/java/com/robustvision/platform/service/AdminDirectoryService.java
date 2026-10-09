package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.domain.UserStatus;
import com.robustvision.platform.dto.AdminDtos;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class AdminDirectoryService {
    private final UserRepository users;
    private final UserService userService;
    private final AdminAuditRepository audit;
    private final JdbcTemplate jdbc;
    public AdminDirectoryService(UserRepository users, UserService userService, AdminAuditRepository audit, JdbcTemplate jdbc) {
        this.users = users; this.userService = userService; this.audit = audit; this.jdbc = jdbc;
    }
    @Transactional(readOnly = true)
    public AdminDtos.Page<ApiDtos.UserView> users(String query, UserStatus status, Long roleId, int page, int size) {
        Pageable pageable = page(page, size);
        String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (text.length() > 120) throw new BusinessException(HttpStatus.BAD_REQUEST, "QUERY_TOO_LONG", "搜索内容最多 120 字符");
        String pattern = "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        Specification<UserEntity> filter = (root, q, cb) -> {
            List<jakarta.persistence.criteria.Predicate> conditions = new ArrayList<>();
            if (!text.isEmpty()) conditions.add(cb.or(cb.like(cb.lower(root.get("username")), pattern, '!'),
                    cb.like(cb.lower(root.get("displayName")), pattern, '!'), cb.like(cb.lower(root.get("email")), pattern, '!'),
                    cb.like(cb.lower(root.get("identityCode")), pattern, '!')));
            if (status != null) conditions.add(cb.equal(root.get("status"), status));
            if (roleId != null) conditions.add(cb.equal(root.get("role").get("id"), roleId));
            return cb.and(conditions.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result = users.findAll(filter, pageable);
        return new AdminDtos.Page<>(result.stream().map(userService::toView).toList(), result.getTotalElements(), page, size);
    }
    @Transactional(readOnly = true)
    public AdminDtos.UserDetail detail(Long id) {
        UserEntity user = users.findById(id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        Instant now = Instant.now();
        Timestamp last = jdbc.queryForObject("SELECT MAX(created_at) FROM user_session WHERE user_id = ?", Timestamp.class, id);
        return new AdminDtos.UserDetail(userService.toView(user), userService.access(user),
                count("SELECT COUNT(*) FROM note WHERE owner_id = ? AND deleted_at IS NULL", id),
                count("SELECT COUNT(*) FROM file_asset WHERE owner_id = ?", id),
                count("SELECT COALESCE(SUM(size_bytes), 0) FROM file_asset WHERE owner_id = ?", id),
                count("SELECT COUNT(*) FROM inference_task WHERE requested_by = ?", id),
                count("SELECT COUNT(*) FROM personal_ai_usage WHERE owner_id = ?", id),
                count("SELECT COUNT(*) FROM user_session WHERE user_id = ? AND revoked_at IS NULL AND expires_at > ?", id, Timestamp.from(now)),
                last == null ? null : last.toInstant());
    }
    @Transactional(readOnly = true)
    public AdminDtos.Page<AdminDtos.Audit> audit(int page, int size) {
        var result = audit.findAll(page(page, size));
        return new AdminDtos.Page<>(result.stream().map(a -> new AdminDtos.Audit(a.getId(), a.getOperatorId(), a.getOperatorName(),
                a.getAction(), a.getTargetType(), a.getTargetId(), a.getDetail(), a.getCreatedAt())).toList(), result.getTotalElements(), page, size);
    }
    private Pageable page(int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "分页参数无效");
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
    private long count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
}
