package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.PersonalAiDtos.*;
import com.robustvision.platform.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalAiServiceTest {
    private final CurrentUserService users = mock(CurrentUserService.class);
    private final PersonalAiSettingRepository settings = mock(PersonalAiSettingRepository.class);
    private final PersonalAiUsageRepository usage = mock(PersonalAiUsageRepository.class);
    private final SecretEncryptionService encryption = new SecretEncryptionService("synthetic-test-only-master-key-at-least-32-bytes");
    private final PersonalAiEndpointPolicy policy = new PersonalAiEndpointPolicy("");
    private final PersonalAiTransport transport = spy(new PersonalAiTransport(new ObjectMapper(), policy));
    private final NoteExperimentSourceService sources = mock(NoteExperimentSourceService.class);
    private final UserEntity a = mock(UserEntity.class), b = mock(UserEntity.class);
    private PersonalAiService service;
    private PersonalAiSettingEntity setting;
    private static final String KEY = "synthetic-user-a-api-key";
    @BeforeEach void init() {
        when(a.getId()).thenReturn(11L); when(b.getId()).thenReturn(22L); when(users.requireCurrent()).thenReturn(a);
        setting = new PersonalAiSettingEntity(11L, AiProvider.OPENAI);
        setting.update("model", "https://api.openai.com/v1", encryption.encrypt(KEY), true);
        when(settings.findByOwnerIdAndProvider(11L, AiProvider.OPENAI)).thenReturn(Optional.of(setting));
        when(settings.findByOwnerIdAndProvider(22L, AiProvider.OPENAI)).thenReturn(Optional.empty());
        when(sources.buildContext(anyList())).thenReturn("");
        doReturn(new PersonalAiTransport.Completion("mock result", 10, 4)).when(transport).execute(any(), any(), anyString());
        service = new PersonalAiService(users, settings, usage, encryption, policy, transport, new PersonalAiRateLimiter(), sources, true);
    }
    private PreviewRequest request() { return new PreviewRequest(AiProvider.OPENAI, "draft", "title", "approved body", List.of()); }
    @Test void previewIsExactImmutableSingleUseAndOwnerBoundEvenForAdmin() throws Exception {
        var preview = service.preview(request());
        assertThat(preview.context()).contains("approved body"); assertThat(preview.systemPrompt()).isEqualTo(PersonalAiService.SYSTEM);
        verify(transport, never()).execute(any(), any(), any());
        when(users.requireCurrent()).thenReturn(b); // No admin path exists, regardless of role.
        assertThatThrownBy(() -> service.execute(new ExecuteRequest(preview.previewToken(), true))).isInstanceOf(BusinessException.class);
        when(users.requireCurrent()).thenReturn(a);
        var result = service.execute(new ExecuteRequest(preview.previewToken(), true));
        assertThat(result.engine()).isEqualTo("PERSONAL_AI:OPENAI"); assertThat(result.result()).isEqualTo("mock result");
        var payload = ArgumentCaptor.forClass(PersonalAiTransport.Payload.class);
        verify(transport).execute(eq(AiProvider.OPENAI), payload.capture(), eq(KEY));
        assertThat(new ObjectMapper().readTree(payload.getValue().json()).path("messages").path(1).path("content").asText()).isEqualTo(preview.context());
        assertThatThrownBy(() -> service.execute(new ExecuteRequest(preview.previewToken(), true))).isInstanceOf(BusinessException.class);
        var logged = ArgumentCaptor.forClass(PersonalAiUsageEntity.class); verify(usage).save(logged.capture());
        assertThat(logged.getValue().getOwnerId()).isEqualTo(11L); assertThat(logged.getValue().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(logged.getValue())).doesNotContain(KEY, "approved body", "mock result");
    }
    @Test void confirmationMissingConfigConfigRotationDeletionExpiryAndRemoteKillSwitchFailClosed() {
        var preview = service.preview(request());
        assertThatThrownBy(() -> service.execute(new ExecuteRequest(preview.previewToken(), false))).isInstanceOf(BusinessException.class);
        ReflectionTestUtils.setField(setting, "revision", 1L);
        assertThatThrownBy(() -> service.execute(new ExecuteRequest(preview.previewToken(), true))).isInstanceOf(BusinessException.class).hasMessageContaining("配置已更改");
        var secondPreview = service.preview(request());
        String token = secondPreview.previewToken();
        when(settings.findByOwnerIdAndProvider(11L, AiProvider.OPENAI)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.execute(new ExecuteRequest(token, true))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.preview(request())).isInstanceOf(BusinessException.class);
        when(settings.findByOwnerIdAndProvider(11L, AiProvider.OPENAI)).thenReturn(Optional.of(setting));
        var expired = service.preview(request());
        ReflectionTestUtils.setField(service, "clock", Clock.offset(Clock.systemUTC(), Duration.ofMinutes(6)));
        assertThatThrownBy(() -> service.execute(new ExecuteRequest(expired.previewToken(), true))).isInstanceOf(BusinessException.class);
        var disabled = new PersonalAiService(users, settings, usage, encryption, policy, transport, new PersonalAiRateLimiter(), sources, false);
        var disabledPreview = disabled.preview(request());
        assertThatThrownBy(() -> disabled.execute(new ExecuteRequest(disabledPreview.previewToken(), true))).isInstanceOf(BusinessException.class).hasMessageContaining("尚未启用");
        verify(transport, never()).execute(any(), any(), any());
    }
    @Test void selectedSourcesAreCheckedBeforePreviewAndContextNeverSilentlyTruncated() {
        when(sources.buildContext(List.of("foreign-id"))).thenThrow(new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "denied"));
        assertThatThrownBy(() -> service.preview(new PreviewRequest(AiProvider.OPENAI, "draft", "", "body", List.of("foreign-id"))))
                .isInstanceOf(BusinessException.class);
        when(sources.buildContext(List.of())).thenReturn("x".repeat(24000));
        assertThatThrownBy(() -> service.preview(request())).isInstanceOf(BusinessException.class).hasMessageContaining("上下文过长");
        verify(transport, never()).execute(any(), any(), any());
    }
    @Test void sourceOwnershipIsRecheckedWithoutMutatingApprovedContent() {
        var req = new PreviewRequest(AiProvider.OPENAI, "draft", "title", "body", List.of("own-task"));
        when(sources.buildContext(List.of("own-task"))).thenReturn("approved source")
                .thenThrow(new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "denied"));
        var preview = service.preview(req);
        assertThatThrownBy(() -> service.execute(new ExecuteRequest(preview.previewToken(), true))).isInstanceOf(BusinessException.class);
        verify(transport, never()).execute(any(), any(), any());
    }

    @Test void upstreamFailureRecordsOnlySanitizedMetadataAndNeverUsesFallback() {
        doThrow(PersonalAiEndpointPolicy.unavailable()).when(transport).execute(any(), any(), any());
        var preview = service.preview(request());
        assertThatThrownBy(() -> service.execute(new ExecuteRequest(preview.previewToken(), true))).isInstanceOf(BusinessException.class);
        var captured = ArgumentCaptor.forClass(PersonalAiUsageEntity.class); verify(usage).save(captured.capture());
        assertThat(captured.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captured.getValue().getErrorCode()).isEqualTo("PERSONAL_AI_UPSTREAM_FAILED");
        assertThat(captured.getValue().getInputTokens()).isNull();
        assertThat(captured.getValue().getOutputTokens()).isNull();
        verify(transport, times(1)).execute(any(), any(), eq(KEY));
    }
    @Test void failedCallPersistsReportedTokensAndUnknownNetworkUsageIsNull() {
        doThrow(new PersonalAiUpstreamFailure(100L, 1600L)).when(transport).execute(any(),any(),any());
        var approved=service.preview(request());
        assertThatThrownBy(()->service.execute(new ExecuteRequest(approved.previewToken(),true))).isInstanceOf(BusinessException.class);
        verify(usage).save(argThat(row -> row.getStatus().equals("FAILED") && Long.valueOf(100).equals(row.getInputTokens())
                && Long.valueOf(1600).equals(row.getOutputTokens())));
    }
    @Test void perUserRateAndConcurrencyLimitsReleaseOnCloseAndKeepUsersSeparate() {
        var limiter = new PersonalAiRateLimiter();
        try (var one = limiter.acquire(11L)) {
            assertThatThrownBy(() -> limiter.acquire(11L)).isInstanceOf(BusinessException.class);
            try (var two = limiter.acquire(22L)) { assertThat(two).isNotNull(); }
        }
        for (int i = 0; i < 11; i++) try (var permit = limiter.acquire(11L)) { assertThat(permit).isNotNull(); }
        assertThatThrownBy(() -> limiter.acquire(11L)).isInstanceOf(BusinessException.class);
        for (int i = 0; i < 30; i++) limiter.preview(22L);
        assertThatThrownBy(() -> limiter.preview(22L)).isInstanceOf(BusinessException.class);
    }
    @Test void localTidyNeverSilentlyTruncatesAndLosesTheTailOfALargeNote() {
        String original="x".repeat(24000)+"IMPORTANT_TAIL";
        assertThatThrownBy(()->new NoteAssistService().assist(new com.robustvision.platform.dto.ApiDtos.NoteAssistRequest("tidy",original,"")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不会截断正文");
    }
    @Test void legacyAssistIsAlwaysLocalWithoutAnyCredentialDependency() {
        var local = new NoteAssistService().assist(new com.robustvision.platform.dto.ApiDtos.NoteAssistRequest("tidy", "#标题\n内容", ""));
        assertThat(local.engine()).isEqualTo("LOCAL_RULES");
    }
}
