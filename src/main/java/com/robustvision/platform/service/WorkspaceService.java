package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.UserRepository;
import com.robustvision.platform.repository.WorkspaceMemberRepository;
import com.robustvision.platform.repository.WorkspaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;

@Service
public class WorkspaceService {
    public static final Set<String> ALL = Set.of("CONTENT_READ", "CONTENT_WRITE", "MEMBERS_READ", "MEMBERS_WRITE", "SETTINGS_WRITE");
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    public WorkspaceService(WorkspaceRepository workspaceRepository, WorkspaceMemberRepository memberRepository,
                            UserRepository userRepository, CurrentUserService currentUserService) {
        this.workspaceRepository = workspaceRepository; this.memberRepository = memberRepository;
        this.userRepository = userRepository; this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.WorkspaceView> list() {
        UserEntity current = currentUserService.requireCurrent();
        boolean admin = currentUserService.isSuperAdmin(current);
        List<WorkspaceRepository.WorkspaceSummary> workspaces = admin
                ? workspaceRepository.findAllSummaries() : workspaceRepository.findMemberSummaries(current.getId());
        if (workspaces.isEmpty()) return List.of();

        // Request-local grouping only: membership changes are reflected on every new listing.
        Map<Long, Map<Long, MemberViewBuilder>> membersByWorkspace = new HashMap<>();
        for (WorkspaceMemberRepository.MemberDetails row : memberRepository.findDetailsByWorkspaceIds(
                workspaces.stream().map(WorkspaceRepository.WorkspaceSummary::getId).toList())) {
            MemberViewBuilder member = membersByWorkspace.computeIfAbsent(row.getWorkspaceId(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(row.getId(), key -> new MemberViewBuilder(row));
            if (row.getPermission() != null) member.permissions.add(row.getPermission());
        }
        return workspaces.stream().map(workspace -> {
            List<ApiDtos.WorkspaceMemberView> members = membersByWorkspace.getOrDefault(workspace.getId(), Map.of())
                    .values().stream().map(MemberViewBuilder::toView).toList();
            ApiDtos.WorkspaceMemberView own = members.stream().filter(member -> current.getId().equals(member.userId()))
                    .findFirst().orElse(null);
            WorkspaceMemberRole currentRole = own == null ? (admin ? WorkspaceMemberRole.ADMIN : null) : own.role();
            Set<String> currentPermissions = admin ? ALL : own == null ? Set.of() : own.permissions();
            return new ApiDtos.WorkspaceView(workspace.getId(), workspace.getName(), workspace.getSlug(), workspace.getColor(),
                    workspace.getOwnerId(), workspace.getOwnerName(), currentRole, currentPermissions,
                    currentPermissions.contains("MEMBERS_READ") ? members : members.stream()
                            .filter(member -> current.getId().equals(member.userId())).toList(),
                    workspace.getCreatedAt(), workspace.getUpdatedAt());
        }).toList();
    }

    @Transactional
    public ApiDtos.WorkspaceView create(ApiDtos.CreateWorkspaceRequest request) {
        UserEntity current = currentUserService.requireCurrent();
        String slug = uniqueSlug(request.name());
        WorkspaceEntity workspace = workspaceRepository.save(new WorkspaceEntity(request.name().trim(), slug, request.color(), current));
        memberRepository.save(new WorkspaceMemberEntity(workspace, current, WorkspaceMemberRole.OWNER, ALL));
        return toView(workspace, current);
    }

    @Transactional(readOnly = true)
    public ApiDtos.WorkspaceView detail(Long id) {
        UserEntity current = currentUserService.requireCurrent(); WorkspaceEntity workspace = requireAccessible(id, current); return toView(workspace, current);
    }

    @Transactional
    public ApiDtos.WorkspaceView update(Long id, ApiDtos.UpdateWorkspaceRequest request) {
        UserEntity current = currentUserService.requireCurrent(); WorkspaceEntity workspace = requireAccessible(id, current);
        requirePermission(workspace, current, "SETTINGS_WRITE"); workspace.update(request.name().trim(), request.color());
        return toView(workspaceRepository.save(workspace), current);
    }

    @Transactional
    public ApiDtos.WorkspaceView upsertMember(Long workspaceId, ApiDtos.WorkspaceMemberRequest request) {
        UserEntity current = currentUserService.requireCurrent(); WorkspaceEntity workspace = requireAccessible(workspaceId, current);
        requirePermission(workspace, current, "MEMBERS_WRITE"); validatePermissions(request.permissions());
        UserEntity user = userRepository.findById(request.userId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        if (request.role() == WorkspaceMemberRole.OWNER && !user.getId().equals(workspace.getOwner().getId()))
            throw new BusinessException(HttpStatus.CONFLICT, "WORKSPACE_OWNER_LOCKED", "不能将其他成员设为所有者");
        WorkspaceMemberEntity member = memberRepository.findByWorkspaceIdAndUserId(workspaceId, user.getId())
                .orElseGet(() -> new WorkspaceMemberEntity(workspace, user, request.role(), defaults(request.role())));
        if (member.getRole() == WorkspaceMemberRole.OWNER && request.role() != WorkspaceMemberRole.OWNER)
            throw new BusinessException(HttpStatus.CONFLICT, "WORKSPACE_OWNER_LOCKED", "不能修改工作空间所有者角色");
        Set<String> permissions = request.permissions() == null || request.permissions().isEmpty() ? defaults(request.role()) : request.permissions();
        if (!currentUserService.isSuperAdmin(current) && !workspace.getOwner().getId().equals(current.getId())) {
            WorkspaceMemberEntity actor = memberRepository.findByWorkspaceIdAndUserId(workspaceId, current.getId()).orElseThrow();
            if (!actor.getPermissions().containsAll(permissions) || member.getRole() == WorkspaceMemberRole.OWNER)
                throw new BusinessException(HttpStatus.FORBIDDEN, "WORKSPACE_PRIVILEGE_ESCALATION", "不能授予自己不具备的权限或修改所有者权限");
        }
        member.update(request.role(), permissions); memberRepository.save(member); return toView(workspace, current);
    }

    @Transactional
    public ApiDtos.WorkspaceView removeMember(Long workspaceId, Long userId) {
        UserEntity current = currentUserService.requireCurrent(); WorkspaceEntity workspace = requireAccessible(workspaceId, current);
        requirePermission(workspace, current, "MEMBERS_WRITE");
        WorkspaceMemberEntity member = memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "WORKSPACE_MEMBER_NOT_FOUND", "成员不存在"));
        if (member.getRole() == WorkspaceMemberRole.OWNER)
            throw new BusinessException(HttpStatus.CONFLICT, "WORKSPACE_OWNER_LOCKED", "不能移除工作空间所有者");
        memberRepository.delete(member); return toView(workspace, current);
    }

    private WorkspaceEntity requireAccessible(Long id, UserEntity current) {
        WorkspaceEntity workspace = workspaceRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND", "工作空间不存在"));
        if (!currentUserService.isSuperAdmin(current) && memberRepository.findByWorkspaceIdAndUserId(id, current.getId()).isEmpty())
            throw new BusinessException(HttpStatus.FORBIDDEN, "WORKSPACE_ACCESS_DENIED", "无权访问该工作空间");
        return workspace;
    }

    private void requirePermission(WorkspaceEntity workspace, UserEntity current, String permission) {
        if (currentUserService.isSuperAdmin(current)) return;
        WorkspaceMemberEntity member = memberRepository.findByWorkspaceIdAndUserId(workspace.getId(), current.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN, "WORKSPACE_ACCESS_DENIED", "无权访问该工作空间"));
        if (!member.getPermissions().contains(permission))
            throw new BusinessException(HttpStatus.FORBIDDEN, "WORKSPACE_PERMISSION_DENIED", "工作空间权限不足");
    }

    private ApiDtos.WorkspaceView toView(WorkspaceEntity workspace, UserEntity current) {
        WorkspaceMemberEntity own = memberRepository.findByWorkspaceIdAndUserId(workspace.getId(), current.getId()).orElse(null);
        WorkspaceMemberRole currentRole = currentUserService.isSuperAdmin(current) && own == null ? WorkspaceMemberRole.ADMIN : own == null ? null : own.getRole();
        Set<String> currentPermissions = currentUserService.isSuperAdmin(current) ? ALL : own == null ? Set.of() : own.getPermissions();
        List<ApiDtos.WorkspaceMemberView> members = memberRepository.findByWorkspaceIdOrderByCreatedAtAsc(workspace.getId()).stream().map(member ->
                new ApiDtos.WorkspaceMemberView(member.getId(), member.getUser().getId(), member.getUser().getUsername(), member.getUser().getDisplayName(),
                        member.getRole(), member.getPermissions(), member.getCreatedAt())).toList();
        return new ApiDtos.WorkspaceView(workspace.getId(), workspace.getName(), workspace.getSlug(), workspace.getColor(),
                workspace.getOwner().getId(), workspace.getOwner().getDisplayName(), currentRole, currentPermissions,
                currentPermissions.contains("MEMBERS_READ") ? members : members.stream()
                        .filter(member -> current.getId().equals(member.userId())).toList(),
                workspace.getCreatedAt(), workspace.getUpdatedAt());
    }

    private static final class MemberViewBuilder {
        private final WorkspaceMemberRepository.MemberDetails details;
        private final Set<String> permissions = new LinkedHashSet<>();

        private MemberViewBuilder(WorkspaceMemberRepository.MemberDetails details) { this.details = details; }

        private ApiDtos.WorkspaceMemberView toView() {
            return new ApiDtos.WorkspaceMemberView(details.getId(), details.getUserId(), details.getUsername(),
                    details.getDisplayName(), details.getRole(), permissions, details.getCreatedAt());
        }
    }

    private void validatePermissions(Set<String> permissions) {
        if (permissions == null) return; Set<String> unknown = new HashSet<>(permissions); unknown.removeAll(ALL);
        if (!unknown.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST, "WORKSPACE_PERMISSION_INVALID", "包含未知工作空间权限: " + unknown);
    }
    private Set<String> defaults(WorkspaceMemberRole role) { return switch (role) {
        case OWNER, ADMIN -> ALL;
        case MEMBER -> Set.of("CONTENT_READ", "CONTENT_WRITE", "MEMBERS_READ");
        case VIEWER -> Set.of("CONTENT_READ");
    }; }
    private String uniqueSlug(String name) {
        String slug = Normalizer.normalize(name, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isBlank()) slug = "workspace";
        String candidate = slug; int suffix = 2;
        while (workspaceRepository.existsBySlug(candidate)) candidate = slug + "-" + suffix++;
        return candidate;
    }
}
