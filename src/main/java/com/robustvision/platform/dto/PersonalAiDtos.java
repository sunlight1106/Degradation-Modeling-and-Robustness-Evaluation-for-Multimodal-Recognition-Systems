package com.robustvision.platform.dto;

import com.robustvision.platform.domain.AiProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class PersonalAiDtos {
    private PersonalAiDtos() {}
    public record ProviderView(AiProvider provider, String displayName, String protocol, List<String> baseUrls, boolean customEndpointAllowed, boolean remoteEnabled) {}
    public record SettingRequest(@NotBlank @Size(max = 160) String model,
            @Size(max = 500) String baseUrl,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Size(max = 4000) String apiKey,
            @NotNull Boolean enabled) {
        @Override public String toString() { return "SettingRequest[REDACTED]"; }
    }
    public record SettingView(AiProvider provider, String model, String baseUrl, boolean enabled,
                              boolean configured, long revision, Instant updatedAt) {}
    public record PreviewRequest(@NotNull AiProvider provider, @NotBlank @Size(max = 20) String action,
            @Size(max = 180) String title, @Size(max = 24000) String body,
            @Size(max = 20) List<@NotBlank @Size(max = 38) String> selectedTaskIds) {
        @Override public String toString() { return "PreviewRequest[REDACTED]"; }
    }
    public record PreviewView(String previewToken, Instant expiresAt, AiProvider provider, String model,
                              String endpoint, String action, String context, String systemPrompt, int outboundBytes) {}
    public record ExecuteRequest(@NotBlank @Size(max = 100) String previewToken, @AssertTrue boolean confirmed) {
        @Override public String toString() { return "ExecuteRequest[REDACTED]"; }
    }
    public record ResultView(String action, String engine, String result, List<String> items, String note,
                             String traceId, Long inputTokens, Long outputTokens) {}
    public record UsageView(String id, AiProvider provider, String model, String action, String status,
                            Long inputTokens, Long outputTokens, String errorCode, Instant createdAt) {}
}
