package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.PersonalAiDtos.*;
import com.robustvision.platform.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false)
@Import({PersonalAiSettingsService.class, CurrentUserService.class,ModerationGuard.class, SecretEncryptionService.class, PersonalAiEndpointPolicy.class})
class PersonalAiIsolationIntegrationTest {
    @Autowired TestEntityManager entities;
    @Autowired PersonalAiSettingsService settingsService;
    @Autowired CurrentUserService currentUser;
    @Autowired PersonalAiSettingRepository settings;
    @Autowired PersonalAiUsageRepository usage;
    @Autowired SecretEncryptionService encryption;
    @Autowired PersonalAiEndpointPolicy endpoints;
    @AfterEach void clearAuth() { SecurityContextHolder.clearContext(); }
    private static void login(String name) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, "", List.of()));
    }
    @Test void twoRealDatabaseUsersIncludingAdminCannotReadExecuteOrDeleteEachOthersProfile() throws Exception {
        var roleA = entities.persist(new RoleEntity("BYOK_A", "researcher", "test", Set.of("note:write", "personal-ai:use")));
        var roleB = entities.persist(new RoleEntity("ADMIN", "admin", "test", Set.of("note:write")));
        var a = entities.persist(new UserEntity("byok_a", "test", "A", "a@byok.test", roleA));
        var b = entities.persist(new UserEntity("byok_b", "test", "B", "b@byok.test", roleB));
        entities.flush();
        login(a.getUsername());
        var response = settingsService.save(AiProvider.OPENAI, new SettingRequest("mock-model", null, "synthetic-isolated-user-a-key", true));
        var stored = settings.findByOwnerIdAndProvider(a.getId(), AiProvider.OPENAI).orElseThrow();
        assertThat(stored.getEncryptedKey()).doesNotContain("synthetic-isolated-user-a-key");
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(response)).doesNotContain("synthetic-isolated-user-a-key", stored.getEncryptedKey());
        var sources = mock(NoteExperimentSourceService.class); when(sources.buildContext(anyList())).thenReturn("");
        var transport = spy(new PersonalAiTransport(new ObjectMapper(), endpoints));
        doReturn(new PersonalAiTransport.Completion("synthetic result", 2, 3)).when(transport).execute(any(), any(), any());
        var ai = new PersonalAiService(currentUser, settings, new PersonalAiPersistenceService(usage, mock(PersonalRecognitionResultRepository.class), mock(org.springframework.transaction.PlatformTransactionManager.class),PersonalAiTestOwners.active()), encryption, endpoints, transport, new PersonalAiRateLimiter(), sources, mock(PersonalAiMemoryService.class), true);
        var preview = ai.preview(new PreviewRequest(AiProvider.OPENAI, "draft", "title", "own content", List.of()));
        login(b.getUsername());
        assertThat(currentUser.isSuperAdmin(currentUser.requireCurrent())).isTrue();
        assertThat(settingsService.list()).isEmpty(); assertThat(settingsService.usage()).isEmpty();
        assertThatThrownBy(() -> ai.execute(new ExecuteRequest(preview.previewToken(), true))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ai.preview(new PreviewRequest(AiProvider.OPENAI, "draft", "title", "body", List.of()))).isInstanceOf(BusinessException.class);
        settingsService.delete(AiProvider.OPENAI); entities.flush();
        assertThat(settings.findByOwnerIdAndProvider(a.getId(), AiProvider.OPENAI)).isPresent();
        assertThat(settings.findByOwnerIdAndProvider(b.getId(), AiProvider.OPENAI)).isEmpty();
        verify(transport, never()).execute(any(), any(), any());
        login(a.getUsername());
        ai.execute(new ExecuteRequest(preview.previewToken(), true));
        assertThat(settingsService.usage()).hasSize(1);
        var stale = ai.preview(new PreviewRequest(AiProvider.OPENAI, "draft", "title", "body", List.of()));
        settingsService.save(AiProvider.OPENAI, new SettingRequest("changed-model", null, null, true));
        assertThatThrownBy(() -> ai.execute(new ExecuteRequest(stale.previewToken(), true))).isInstanceOf(BusinessException.class).hasMessageContaining("配置已更改");
        login(b.getUsername()); assertThat(settingsService.usage()).isEmpty();
        login(a.getUsername()); settingsService.delete(AiProvider.OPENAI); entities.flush();
        assertThat(settings.findByOwnerIdAndProvider(a.getId(), AiProvider.OPENAI)).isEmpty();
        verify(transport).execute(eq(AiProvider.OPENAI), any(), eq("synthetic-isolated-user-a-key"));
    }
}
