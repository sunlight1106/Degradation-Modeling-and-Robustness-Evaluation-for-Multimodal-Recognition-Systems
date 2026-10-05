package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.PersonalAiDtos.*;
import com.robustvision.platform.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class PersonalAiSettingsService {
    private final CurrentUserService currentUser;
    private final PersonalAiSettingRepository settings;
    private final PersonalAiUsageRepository usage;
    private final SecretEncryptionService encryption;
    private final PersonalAiEndpointPolicy endpoints;
    private final boolean remoteEnabled;
    public PersonalAiSettingsService(CurrentUserService currentUser, PersonalAiSettingRepository settings,
                                     PersonalAiUsageRepository usage, SecretEncryptionService encryption,
                                     PersonalAiEndpointPolicy endpoints, @Value("${app.personal-ai.remote-enabled:false}") boolean remoteEnabled) {
        this.currentUser = currentUser; this.settings = settings; this.usage = usage;
        this.encryption = encryption; this.endpoints = endpoints; this.remoteEnabled = remoteEnabled;
    }
    public List<ProviderView> providers() {
        currentUser.requireCurrent();
        return Arrays.stream(AiProvider.values()).map(p -> new ProviderView(p, p.displayName(), p.protocol().name(),
                p.baseUrls(), endpoints.customEndpointAllowed(), remoteEnabled)).toList();
    }
    public List<SettingView> list() {
        return settings.findByOwnerIdOrderByProviderAsc(currentUser.requireCurrent().getId()).stream().map(this::view).toList();
    }
    @Transactional
    public SettingView save(AiProvider provider, SettingRequest request) {
        Long owner = currentUser.requireCurrent().getId();
        String model = request.model() == null ? "" : request.model().trim();
        if (!model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}") || model.contains("..")
                || (provider == AiProvider.GEMINI && !model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")))
            throw invalid("PERSONAL_AI_MODEL_INVALID", "请填写有效的模型 ID，Gemini 仅填写模型名称");
        String base = endpoints.validateBase(provider, request.baseUrl());
        PersonalAiSettingEntity entity = settings.findByOwnerIdAndProvider(owner, provider)
                .orElseGet(() -> new PersonalAiSettingEntity(owner, provider));
        String cipher = entity.getEncryptedKey();
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            if (!request.apiKey().matches("[\\x21-\\x7e]{8,4000}"))
                throw invalid("PERSONAL_AI_KEY_INVALID", "密钥格式无效，请使用供应商 API 密钥");
            cipher = encryption.encrypt(request.apiKey());
        }
        if (cipher == null || cipher.isBlank()) throw invalid("PERSONAL_AI_KEY_REQUIRED", "请先配置自己的 API 密钥");
        if (request.enabled() == null) throw invalid("PERSONAL_AI_SETTING_INVALID", "请选择是否启用个人配置");
        entity.update(model, base, cipher, request.enabled());
        return view(settings.saveAndFlush(entity));
    }
    @Transactional
    public void delete(AiProvider provider) {
        settings.findByOwnerIdAndProvider(currentUser.requireCurrent().getId(), provider).ifPresent(settings::delete);
    }
    public List<UsageView> usage() {
        return usage.findTop100ByOwnerIdOrderByCreatedAtDesc(currentUser.requireCurrent().getId()).stream()
                .map(u -> new UsageView(u.getId(), u.getProvider(), u.getModel(), u.getAction(), u.getStatus(),
                        u.getInputTokens(), u.getOutputTokens(), u.getErrorCode(), u.getCreatedAt())).toList();
    }
    private SettingView view(PersonalAiSettingEntity s) {
        return new SettingView(s.getProvider(), s.getModel(), s.getBaseUrl(), s.isEnabled(),
                s.getEncryptedKey() != null && !s.getEncryptedKey().isBlank(), s.getRevision(), s.getUpdatedAt());
    }
    private static BusinessException invalid(String code, String message) { return new BusinessException(HttpStatus.BAD_REQUEST, code, message); }
}
