package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.service.WorkspaceService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/workspaces")
@PreAuthorize("hasAuthority('workspace:manage')")
public class WorkspaceController {
    private final WorkspaceService service;
    public WorkspaceController(WorkspaceService service) { this.service = service; }
    @GetMapping public ApiResponse<List<ApiDtos.WorkspaceView>> list() { return ApiResponse.ok(service.list()); }
    @PostMapping public ApiResponse<ApiDtos.WorkspaceView> create(@Valid @RequestBody ApiDtos.CreateWorkspaceRequest request) { return ApiResponse.ok(service.create(request)); }
    @GetMapping("/{id}") public ApiResponse<ApiDtos.WorkspaceView> detail(@PathVariable Long id) { return ApiResponse.ok(service.detail(id)); }
    @PatchMapping("/{id}") public ApiResponse<ApiDtos.WorkspaceView> update(@PathVariable Long id, @Valid @RequestBody ApiDtos.UpdateWorkspaceRequest request) { return ApiResponse.ok(service.update(id, request)); }
    @PutMapping("/{id}/members") public ApiResponse<ApiDtos.WorkspaceView> member(@PathVariable Long id, @Valid @RequestBody ApiDtos.WorkspaceMemberRequest request) { return ApiResponse.ok(service.upsertMember(id, request)); }
    @DeleteMapping("/{id}/members/{userId}") public ApiResponse<ApiDtos.WorkspaceView> remove(@PathVariable Long id, @PathVariable Long userId) { return ApiResponse.ok(service.removeMember(id, userId)); }
}
