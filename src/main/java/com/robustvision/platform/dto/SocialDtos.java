package com.robustvision.platform.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class SocialDtos {
    private SocialDtos() {}
    public record Person(Long id, String identityCode, String username, String displayName) {}
    public record Contact(Long id, Long userId, String identityCode, String username, String displayName, String status, boolean incoming, boolean blockedByMe, boolean available) {}
    public record Discoverability(boolean discoverable) {}
    public record ContactRequest(@NotNull @Positive Long userId) {}
    public record Action(@NotNull @Pattern(regexp = "accept|reject|remove|block|unblock") String action) {}
    public record ChatRequest(@NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String clientId, @NotBlank @Size(max = 4000) String body) {}
    public record ChatMessage(Long id, Long senderId, String senderName, String clientId, String body, Instant createdAt) {}
}
