package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class AdminController {
    private final UserService userService;

    public AdminController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('user:read')")
    public ApiResponse<List<ApiDtos.UserView>> users() {
        return ApiResponse.ok(userService.listUsers());
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('user:write')")
    public ApiResponse<ApiDtos.UserView> createUser(@Valid @RequestBody ApiDtos.CreateUserRequest request) {
        return ApiResponse.ok(userService.create(request));
    }

    @PatchMapping("/users/{id}")
    @PreAuthorize("hasAuthority('user:write')")
    public ApiResponse<ApiDtos.UserView> updateUser(@PathVariable Long id,
                                                    @Valid @RequestBody ApiDtos.UpdateUserRequest request) {
        return ApiResponse.ok(userService.update(id, request));
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('role:read')")
    public ApiResponse<List<ApiDtos.RoleView>> roles() {
        return ApiResponse.ok(userService.listRoles());
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('role:read')")
    public ApiResponse<List<ApiDtos.PermissionView>> permissions() {
        return ApiResponse.ok(userService.listPermissions());
    }

    @PutMapping("/roles/{id}/permissions")
    @PreAuthorize("hasAuthority('role:write')")
    public ApiResponse<ApiDtos.RoleView> updatePermissions(
            @PathVariable Long id,
            @Valid @RequestBody ApiDtos.UpdatePermissionsRequest request) {
        return ApiResponse.ok(userService.updatePermissions(id, request.permissions()));
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('role:write')")
    public ApiResponse<ApiDtos.RoleView> createRole(@Valid @RequestBody ApiDtos.CreateRoleRequest request) {
        return ApiResponse.ok(userService.createRole(request));
    }
}
