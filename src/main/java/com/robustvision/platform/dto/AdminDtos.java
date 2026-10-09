package com.robustvision.platform.dto;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class AdminDtos {
    private AdminDtos() {}
    public record Page<T>(List<T> items, long total, int page, int size) {}
    public record AccessRequest(@NotNull @Size(max = 100) Set<@NotBlank @Size(max = 80) String> grants, @NotNull @Size(max = 100) Set<@NotBlank @Size(max = 80) String> denies, Instant expiresAt) {}
    public record Access(Set<String> rolePermissions, Set<String> grants, Set<String> denies, Set<String> effectivePermissions, Instant expiresAt) {}
    public record UserDetail(ApiDtos.UserView user, Access access, long notes, long files, long storageBytes,
                             long experiments, long aiCalls, long activeSessions, Instant lastLoginAt) {}
    public record Day(LocalDate date, long registrations, long loginUsers, long notes, long aiCalls, long experiments) {}
    public record Statistics(long users, long enabledUsers, long expiredUsers, long disabledUsers, long recentUsers,
                             long roles, long notes, long files, long storageBytes, long aiCalls, long experiments,
                             String zone, Instant generatedAt, List<Day> days) {}
    public record Audit(long id, long operatorId, String operatorName, String action, String targetType,
                        long targetId, String detail, Instant createdAt) {}
}
