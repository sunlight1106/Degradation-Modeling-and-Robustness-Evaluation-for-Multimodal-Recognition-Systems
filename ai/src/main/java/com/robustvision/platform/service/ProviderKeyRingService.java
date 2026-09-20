package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.ModelProvider;
import com.robustvision.platform.repository.ProviderCredentialRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;

@Service
public class ProviderKeyRingService {
    private final Map<ModelProvider, List<String>> rings = new EnumMap<>(ModelProvider.class);
    private final Map<ModelProvider, AtomicInteger> positions = new EnumMap<>(ModelProvider.class);
    private final Map<ModelProvider, Instant> lastRotation = new EnumMap<>(ModelProvider.class);
    private final ProviderCredentialRepository credentialRepository;
    private final SecretEncryptionService encryption;

    public ProviderKeyRingService(
            @Value("${app.model.deepseek.api-keys:}") String deepSeek,
            @Value("${app.model.kimi.api-keys:}") String kimi,
            @Value("${app.model.qwen.api-keys:}") String qwen,
            ProviderCredentialRepository credentialRepository,
            SecretEncryptionService encryption) {
        this.credentialRepository = credentialRepository;
        this.encryption = encryption;
        rings.put(ModelProvider.DEEPSEEK, parse(deepSeek));
        rings.put(ModelProvider.KIMI, parse(kimi));
        rings.put(ModelProvider.QWEN, parse(qwen));
        rings.keySet().forEach(provider -> positions.put(provider, new AtomicInteger()));
    }

    public String next(ModelProvider provider) {
        List<String> keys = keys(provider);
        if (keys.isEmpty()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_API_KEY_MISSING",
                    displayName(provider) + " API 密钥尚未配置");
        }
        int index = Math.floorMod(positions.get(provider).getAndIncrement(), keys.size());
        lastRotation.put(provider, Instant.now());
        return keys.get(index);
    }

    public boolean configured(ModelProvider provider) { return count(provider) > 0; }
    public int count(ModelProvider provider) { return keys(provider).size(); }
    public Instant lastRotation(ModelProvider provider) { return lastRotation.get(provider); }

    public String displayName(ModelProvider provider) {
        return switch (provider) {
            case DEEPSEEK -> "DeepSeek";
            case KIMI -> "Kimi";
            case QWEN -> "通义千问";
            case CUSTOM -> "Custom HTTP";
            case DEMO -> "Built-in demo";
        };
    }

    private List<String> parse(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
    }

    private List<String> keys(ModelProvider provider) {
        List<String> result = new ArrayList<>(rings.getOrDefault(provider, List.of()));
        credentialRepository.findByProviderAndActiveTrueOrderByCreatedAtAsc(provider).forEach(item -> {
            try { result.add(encryption.decrypt(item.getEncryptedSecret())); }
            catch (BusinessException ignored) { }
        });
        return result.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }
}
