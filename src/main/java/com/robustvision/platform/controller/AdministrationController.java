package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.domain.UserStatus;
import com.robustvision.platform.dto.*;
import com.robustvision.platform.service.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin")
public class AdministrationController {
    private final AdminStatisticsService statistics;
    private final AdminDirectoryService directory;
    private final UserService users;
    public AdministrationController(AdminStatisticsService statistics, AdminDirectoryService directory, UserService users) {
        this.statistics = statistics; this.directory = directory; this.users = users;
    }
    @GetMapping("/statistics") @PreAuthorize("hasAuthority('admin:stats')")
    public ApiResponse<AdminDtos.Statistics> statistics(@RequestParam(defaultValue = "30") int days) { return ApiResponse.ok(statistics.statistics(days)); }
    @GetMapping("/users") @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<AdminDtos.Page<ApiDtos.UserView>> users(@RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) UserStatus status, @RequestParam(required = false) Long roleId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(directory.users(q, status, roleId, page, size));
    }
    @GetMapping("/users/{id}") @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<AdminDtos.UserDetail> detail(@PathVariable Long id) { return ApiResponse.ok(directory.detail(id)); }
    @PutMapping("/users/{id}/access") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AdminDtos.Access> access(@PathVariable Long id, @Valid @RequestBody AdminDtos.AccessRequest request) { return ApiResponse.ok(users.updateAccess(id, request)); }
    @PostMapping("/users/{id}/revoke-sessions") @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> revoke(@PathVariable Long id) { users.revokeUserSessions(id); return ApiResponse.ok(null); }
    @GetMapping("/audit") @PreAuthorize("hasAuthority('admin:audit')")
    public ApiResponse<AdminDtos.Page<AdminDtos.Audit>> audit(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) { return ApiResponse.ok(directory.audit(page, size)); }
}
