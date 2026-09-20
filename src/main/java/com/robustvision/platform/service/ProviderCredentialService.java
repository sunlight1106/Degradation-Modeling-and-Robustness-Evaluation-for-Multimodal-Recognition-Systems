package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.ModelProvider;
import com.robustvision.platform.domain.ProviderCredentialEntity;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.ProviderCredentialRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Service
public class ProviderCredentialService {
    private final ProviderCredentialRepository repository;
    private final SecretEncryptionService encryption;
    private final CurrentUserService currentUserService;

    public ProviderCredentialService(ProviderCredentialRepository repository, SecretEncryptionService encryption,
                                     CurrentUserService currentUserService) {
        this.repository = repository; this.encryption = encryption; this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.ProviderCredentialView> list() {
        currentUserService.requireSuperAdmin();
        return repository.findAll().stream().map(this::toView).toList();
    }

    @Transactional
    public ApiDtos.ProviderCredentialView create(ApiDtos.CreateProviderCredentialRequest request) {
        currentUserService.requireSuperAdmin();
        if (request.provider() != ModelProvider.DEEPSEEK && request.provider() != ModelProvider.KIMI && request.provider() != ModelProvider.QWEN) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PROVIDER_INVALID", "只支持 DeepSeek、Kimi 和通义千问密钥");
        }
        String key = request.apiKey().trim();
        ProviderCredentialEntity entity = new ProviderCredentialEntity(request.provider(), request.label().trim(),
                encryption.encrypt(key), fingerprint(key), currentUserService.requireCurrent());
        return toView(repository.save(entity));
    }

    @Transactional
    public ApiDtos.ProviderCredentialView disable(Long id) {
        currentUserService.requireSuperAdmin();
        ProviderCredentialEntity entity = repository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "模型密钥不存在"));
        entity.disable();
        return toView(repository.save(entity));
    }

    private ApiDtos.ProviderCredentialView toView(ProviderCredentialEntity entity) {
        return new ApiDtos.ProviderCredentialView(entity.getId(), entity.getProvider(), entity.getLabel(), entity.getFingerprint(),
                entity.isActive(), entity.getCreatedBy().getDisplayName(), entity.getCreatedAt(), entity.getDisabledAt());
    }

    private String fingerprint(String value) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
            return digest.substring(0, 12);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
