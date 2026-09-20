package com.robustvision.platform.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.robustvision.platform.domain.FileSource;
import com.robustvision.platform.domain.FileScanStatus;
import com.robustvision.platform.domain.InferenceStatus;
import com.robustvision.platform.domain.ModelProvider;
import com.robustvision.platform.domain.ModelStatus;
import com.robustvision.platform.domain.PaymentMethod;
import com.robustvision.platform.domain.RechargeStatus;
import com.robustvision.platform.domain.TaskType;
import com.robustvision.platform.domain.UserStatus;
import com.robustvision.platform.domain.WorkspaceMemberRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public final class ApiDtos {
    private ApiDtos() {}

    public record LoginRequest(
            @NotBlank @Size(max = 60) String username,
            @NotBlank @Size(max = 100) String password
    ) {}

    public record LoginResponse(String token, String tokenType, Instant expiresAt, UserView user) {}

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,60}$", message = "用户名只能包含字母、数字、点、下划线或连字符") String username,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(min = 8, max = 72) String password
    ) {}

    public record UserView(
            Long id,
            String username,
            String displayName,
            String email,
            UserStatus status,
            Long roleId,
            String roleCode,
            String roleName,
            Set<String> permissions,
            Instant createdAt
    ) {}

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,60}$", message = "只能包含字母、数字、点、下划线或连字符，长度至少 3 位") String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 80) String displayName,
            @NotBlank @Email @Size(max = 160) String email,
            @NotNull Long roleId
    ) {}

    public record UpdateUserRequest(
            @Size(min = 1, max = 80) String displayName,
            @Email @Size(max = 160) String email,
            @Size(min = 8, max = 72) String password,
            UserStatus status,
            Long roleId
    ) {}

    public record RoleView(
            Long id,
            String code,
            String name,
            String description,
            Set<String> permissions,
            Instant createdAt
    ) {}

    public record PermissionView(String code, String label, String group) {}

    public record UpdatePermissionsRequest(@NotNull Set<@NotBlank String> permissions) {}

    public record CreateRoleRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,39}$", message = "角色编码需使用大写字母、数字或下划线") String code,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 255) String description,
            Set<String> permissions
    ) {}

    public record FileView(
            String id,
            String originalName,
            String contentType,
            long sizeBytes,
            String sha256,
            FileSource source,
            FileScanStatus scanStatus,
            String scanEngine,
            String storageBackend,
            String ownerName,
            Instant createdAt,
            String contentUrl,
            String downloadUrl
    ) {}

    public record ModelView(
            Long id,
            String code,
            String name,
            String version,
            ModelProvider provider,
            TaskType taskType,
            ModelStatus status,
            String description,
            String officialDocsUrl,
            String providerConsoleUrl,
            Instant createdAt
    ) {}

    public record CreateInferenceRequest(
            @NotBlank String fileId,
            @NotNull Long modelId,
            @NotNull TaskType taskType,
            boolean enhancementEnabled
    ) {}

    public record InferenceView(
            String id,
            String traceId,
            TaskType taskType,
            InferenceStatus status,
            boolean enhancementEnabled,
            FileView inputFile,
            FileView outputFile,
            ModelView model,
            String requestedBy,
            Double baselineConfidence,
            Double optimizedConfidence,
            Long baselineLatencyMs,
            Long optimizedLatencyMs,
            ModelProvider provider,
            Long inputTokens,
            Long outputTokens,
            BigDecimal costCny,
            JsonNode baselineResult,
            JsonNode optimizedResult,
            String errorMessage,
            Instant createdAt,
            Instant completedAt,
            String reportUrl
    ) {}

    public record DashboardSummary(
            long totalTasks,
            long completedTasks,
            long failedTasks,
            double successRate,
            double averageConfidenceLift,
            List<InferenceView> recentTasks
    ) {}

    public record WalletView(
            BigDecimal balanceCny,
            BigDecimal monthlyQuotaCny,
            BigDecimal monthSpentCny,
            BigDecimal remainingQuotaCny,
            double quotaProgressPercent,
            LocalDate quotaPeriodStart,
            Instant updatedAt
    ) {}

    public record WalletLedgerView(
            Long id, String type, BigDecimal amount, BigDecimal balanceAfter,
            String referenceId, String description, Instant createdAt
    ) {}

    public record CreateRechargeRequest(
            @NotNull @DecimalMin("1.00") @DecimalMax("10000.00") BigDecimal amount,
            @NotNull PaymentMethod method,
            @Size(max = 4) String bankLast4,
            @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入有效的中国大陆手机号") String phone
    ) {}

    public record ConfirmRechargeRequest(
            @NotBlank @Pattern(regexp = "^\\d{6}$", message = "请输入 6 位验证码") String verificationCode
    ) {}

    public record RechargeOrderView(
            String id, PaymentMethod method, BigDecimal amount, RechargeStatus status,
            String bankLast4, String phoneMasked, int verificationAttemptsRemaining,
            String qrPayload, String paymentToken, Instant createdAt, Instant expiresAt, Instant paidAt,
            String sandboxVerificationCode, boolean sandbox
    ) {}

    public record PublicPaymentView(
            String orderId, PaymentMethod method, BigDecimal amount, RechargeStatus status,
            String merchantName, Instant expiresAt, Instant paidAt
    ) {}

    public record BillingSummary(
            WalletView wallet,
            List<RechargeOrderView> recentRecharges,
            List<WalletLedgerView> recentLedger
    ) {}

    public record ProviderBudgetView(
            ModelProvider provider,
            String displayName,
            BigDecimal monthlyBudgetCny,
            BigDecimal usedCny,
            BigDecimal remainingCny,
            double progressPercent,
            BigDecimal providerReportedBalance,
            String balanceSource,
            int configuredKeyCount,
            String rotationMode,
            Instant lastRotationAt,
            Instant updatedAt
    ) {}

    public record UpdateProviderBudgetRequest(@NotNull @DecimalMin("0.00") @DecimalMax("10000000.00") BigDecimal monthlyBudgetCny) {}

    public record AdminWalletView(Long userId, String username, String displayName, String roleCode, WalletView wallet) {}

    public record AdjustWalletRequest(
            @NotNull @DecimalMin("-1000000.00") @DecimalMax("1000000.00") BigDecimal balanceDelta,
            @DecimalMin("0.00") @DecimalMax("10000000.00") BigDecimal monthlyQuotaCny,
            @NotBlank @Size(max = 180) String reason
    ) {}

    public record CreateProviderCredentialRequest(
            @NotNull ModelProvider provider,
            @NotBlank @Size(max = 80) String label,
            @NotBlank @Size(min = 8, max = 500) String apiKey
    ) {}

    public record ProviderCredentialView(
            Long id, ModelProvider provider, String label, String fingerprint,
            boolean active, String createdBy, Instant createdAt, Instant disabledAt
    ) {}

    public record CreateWorkspaceRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "颜色必须是 #RRGGBB") String color
    ) {}

    public record UpdateWorkspaceRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "颜色必须是 #RRGGBB") String color
    ) {}

    public record WorkspaceMemberRequest(
            @NotNull Long userId,
            @NotNull WorkspaceMemberRole role,
            Set<String> permissions
    ) {}

    public record WorkspaceMemberView(
            Long id, Long userId, String username, String displayName,
            WorkspaceMemberRole role, Set<String> permissions, Instant createdAt
    ) {}

    public record WorkspaceView(
            Long id, String name, String slug, String color,
            Long ownerId, String ownerName, WorkspaceMemberRole currentRole,
            Set<String> currentPermissions, List<WorkspaceMemberView> members,
            Instant createdAt, Instant updatedAt
    ) {}

    public record UserDirectoryView(Long id, String username, String displayName, String email) {}

    public record MessageAttachmentView(Long id, String fileName, String contentType, long sizeBytes, String downloadUrl) {}

    public record MessageView(
            String id, Long senderId, String senderName, String subject, String body,
            List<UserDirectoryView> recipients, List<MessageAttachmentView> attachments,
            boolean read, Instant createdAt
    ) {}

    // ------------------------------------------------------------------
    // 知识库与笔记
    // ------------------------------------------------------------------

    public record KnowledgeTopicView(
            Long id, String domain, String name, String description,
            boolean builtin, int sortOrder, long entryCount,
            Instant createdAt, Instant updatedAt
    ) {}

    public record CreateKnowledgeTopicRequest(
            @NotBlank @Size(max = 40) String domain,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            Integer sortOrder
    ) {}

    public record UpdateKnowledgeTopicRequest(
            @Size(min = 1, max = 40) String domain,
            @Size(min = 1, max = 120) String name,
            @Size(max = 500) String description,
            Integer sortOrder
    ) {}

    public record KnowledgeEntryView(
            String id, Long topicId, String topicName, String domain,
            String title, String summary, String body, List<String> tags,
            boolean builtin, long noteReferences, int sortOrder,
            Instant createdAt, Instant updatedAt
    ) {}

    public record CreateKnowledgeEntryRequest(
            @NotNull Long topicId,
            @NotBlank @Size(max = 180) String title,
            @Size(max = 500) String summary,
            @NotBlank String body,
            @Size(max = 500) String tags,
            Integer sortOrder
    ) {}

    public record UpdateKnowledgeEntryRequest(
            Long topicId,
            @Size(min = 1, max = 180) String title,
            @Size(max = 500) String summary,
            String body,
            @Size(max = 500) String tags,
            Integer sortOrder
    ) {}

    public record NoteView(
            String id, String title, String body, List<String> tags,
            NoteStatusView status, List<NoteReferenceView> references,
            int shareCount, Instant createdAt, Instant updatedAt
    ) {}

    /** 笔记列表使用的精简视图，不含正文，减少传输体积。 */
    public record NoteSummaryView(
            String id, String title, String excerpt, List<String> tags,
            NoteStatusView status, int shareCount, Instant createdAt, Instant updatedAt
    ) {}

    public record NoteStatusView(String code, String label) {}

    public record CreateNoteRequest(
            @NotBlank @Size(max = 180) String title,
            @NotBlank String body,
            @Size(max = 500) String tags,
            String status
    ) {}

    public record UpdateNoteRequest(
            @Size(min = 1, max = 180) String title,
            String body,
            @Size(max = 500) String tags,
            String status
    ) {}

    public record NoteReferenceView(
            Long id, String referenceType, String referenceId, String label,
            String displayTitle, String displayMeta, boolean accessible
    ) {}

    public record AddNoteReferenceRequest(
            @NotBlank String referenceType,
            @NotBlank @Size(max = 36) String referenceId,
            @Size(max = 180) String label
    ) {}

    /**
     * AI 整理请求。engine 会在响应中如实标注实际使用的引擎，
     * 不配置供应商密钥时返回 LOCAL_RULES，不伪装成模型输出。
     */
    public record NoteAssistRequest(
            @NotBlank String action,
            @Size(max = 4000) String body,
            String title
    ) {}

    public record NoteAssistResponse(
            String action, String engine, String result,
            List<String> items, String note, String traceId
    ) {}

    public record CreateNoteShareRequest(
            @Size(max = 120) String label,
            Integer expiresInDays
    ) {}

    public record NoteShareView(
            String id, String token, String label, String shareUrl,
            boolean active, int viewCount, Instant expiresAt, Instant createdAt
    ) {}

    /** 分享只读视图，供登录用户查看他人分享的笔记。 */
    public record SharedNoteView(
            String title, String body, List<String> tags, String ownerName,
            Instant createdAt, Instant updatedAt, List<NoteReferenceView> references
    ) {}
}
