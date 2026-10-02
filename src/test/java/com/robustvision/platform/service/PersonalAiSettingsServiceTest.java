package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.PersonalAiDtos.*;
import com.robustvision.platform.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalAiSettingsServiceTest {
    @Test void writesOnlyEncryptedKeyResponsesAndListsNeverExposeItAndDeleteIsOwnScoped() throws Exception {
        var current = mock(CurrentUserService.class); var repo = mock(PersonalAiSettingRepository.class);
        var usage = mock(PersonalAiUsageRepository.class); var user = mock(UserEntity.class);
        when(user.getId()).thenReturn(7L); when(current.requireCurrent()).thenReturn(user);
        when(repo.findByOwnerIdAndProvider(7L, AiProvider.OPENAI)).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var encryption = new SecretEncryptionService("synthetic-test-only-encryption-secret-32-bytes");
        var service = new PersonalAiSettingsService(current, repo, usage, encryption, new PersonalAiEndpointPolicy(""), false);
        var req = new SettingRequest("model", null, "synthetic-private-key", true);
        var result = service.save(AiProvider.OPENAI, req);
        var capture = ArgumentCaptor.forClass(PersonalAiSettingEntity.class); verify(repo).saveAndFlush(capture.capture());
        var saved = capture.getValue();
        assertThat(saved.getOwnerId()).isEqualTo(7L); assertThat(saved.getEncryptedKey()).doesNotContain(req.apiKey());
        assertThat(encryption.decrypt(saved.getEncryptedKey())).isEqualTo(req.apiKey());
        var mapper = new ObjectMapper().findAndRegisterModules();
        assertThat(mapper.writeValueAsString(result)).doesNotContain(req.apiKey(), saved.getEncryptedKey(), "apiKey");
        assertThat(mapper.writeValueAsString(saved)).doesNotContain(req.apiKey(), saved.getEncryptedKey(), "encryptedKey");
        assertThat(req.toString()).doesNotContain(req.apiKey());
        when(repo.findByOwnerIdAndProvider(7L, AiProvider.OPENAI)).thenReturn(Optional.of(saved));
        String cipher = saved.getEncryptedKey(); service.save(AiProvider.OPENAI, new SettingRequest("updated-model", null, "", true));
        assertThat(saved.getEncryptedKey()).isEqualTo(cipher);
        when(repo.findByOwnerIdOrderByProviderAsc(7L)).thenReturn(List.of(saved));
        assertThat(service.list()).hasSize(1); service.delete(AiProvider.OPENAI); verify(repo).delete(saved);
        when(user.getId()).thenReturn(8L);
        when(repo.findByOwnerIdAndProvider(8L, AiProvider.OPENAI)).thenReturn(Optional.empty());
        when(repo.findByOwnerIdOrderByProviderAsc(8L)).thenReturn(List.of());
        assertThat(service.list()).isEmpty(); service.delete(AiProvider.OPENAI);
        verify(repo, times(1)).delete(any()); verify(current, never()).isSuperAdmin(any());
        assertThatThrownBy(() -> service.save(AiProvider.OPENAI, new SettingRequest("model", null, null, true))).isInstanceOf(BusinessException.class);
    }
    @Test void encryptionRejectsMissingWeakKnownKeysAndWrongEncryptionKey() {
        for (String bad : List.of("", "short", "development-credential-master-key-change-me"))
            assertThatThrownBy(() -> new SecretEncryptionService(bad)).isInstanceOf(IllegalStateException.class);
        var first = new SecretEncryptionService("synthetic-master-key-one-with-32-bytes");
        var second = new SecretEncryptionService("synthetic-master-key-two-with-32-bytes");
        assertThatThrownBy(() -> second.decrypt(first.encrypt("synthetic-api-key"))).isInstanceOf(BusinessException.class).hasMessageNotContaining("synthetic");
        assertThat(first.encrypt("same-value")).isNotEqualTo(first.encrypt("same-value"));
    }
}
