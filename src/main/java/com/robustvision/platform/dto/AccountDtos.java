package com.robustvision.platform.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class AccountDtos {
    private AccountDtos() {}
    public record ProfileUpdateRequest(
            @Size(min = 1, max = 80) String displayName,
            @Email @Size(min = 3, max = 160) String email,
            @Size(max = 100) String currentPassword) {
        @Override public String toString() { return "ProfileUpdateRequest[redacted]"; }
    }
    public record PasswordChangeRequest(
            @NotBlank @Size(max = 100) String currentPassword,
            @NotBlank @Size(min = 8, max = 72) String newPassword) {
        @Override public String toString() { return "PasswordChangeRequest[redacted]"; }
    }
    public record PasswordConfirmationRequest(@NotBlank @Size(max = 100) String currentPassword) {
        @Override public String toString() { return "PasswordConfirmationRequest[redacted]"; }
    }
    public record SessionView(String id, Instant createdAt, Instant expiresAt, boolean current,
                              String userAgent, Instant lastSeenAt) {}
}
