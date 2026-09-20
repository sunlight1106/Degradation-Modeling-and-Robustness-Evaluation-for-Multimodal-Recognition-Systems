package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.robustvision.platform.domain.ModelProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Service
public class ProviderBalanceService {
    private final ProviderKeyRingService keyRing;
    private final String deepSeekBaseUrl;
    private final String kimiBaseUrl;

    public ProviderBalanceService(ProviderKeyRingService keyRing,
                                  @Value("${app.model.deepseek.base-url:https://api.deepseek.com}") String deepSeekBaseUrl,
                                  @Value("${app.model.kimi.base-url:https://api.moonshot.cn/v1}") String kimiBaseUrl) {
        this.keyRing = keyRing;
        this.deepSeekBaseUrl = deepSeekBaseUrl;
        this.kimiBaseUrl = kimiBaseUrl;
    }

    public ReportedBalance fetch(ModelProvider provider) {
        if (!keyRing.configured(provider)) return null;
        try {
            return switch (provider) {
                case DEEPSEEK -> deepSeek();
                case KIMI -> kimi();
                default -> null;
            };
        } catch (Exception ignored) { return null; }
    }

    private ReportedBalance deepSeek() {
        JsonNode response = get(deepSeekBaseUrl, "/user/balance", ModelProvider.DEEPSEEK);
        for (JsonNode item : response.path("balance_infos")) {
            if ("CNY".equalsIgnoreCase(item.path("currency").asText())) {
                return new ReportedBalance(decimal(item.path("total_balance")), "CNY", "PROVIDER_API");
            }
        }
        JsonNode first = response.path("balance_infos").path(0);
        return first.isMissingNode() ? null : new ReportedBalance(decimal(first.path("total_balance")), first.path("currency").asText("CNY"), "PROVIDER_API");
    }

    private ReportedBalance kimi() {
        JsonNode response = get(kimiBaseUrl, "/users/me/balance", ModelProvider.KIMI);
        JsonNode data = response.has("data") ? response.path("data") : response;
        JsonNode value = data.has("available_balance") ? data.path("available_balance") : data.path("balance");
        return value.isMissingNode() ? null : new ReportedBalance(decimal(value), "CNY", "PROVIDER_API");
    }

    private JsonNode get(String baseUrl, String path, ModelProvider provider) {
        return RestClient.create(baseUrl).get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + keyRing.next(provider))
                .retrieve().body(JsonNode.class);
    }

    private BigDecimal decimal(JsonNode node) {
        try { return new BigDecimal(node.asText("0")); }
        catch (NumberFormatException ignored) { return BigDecimal.ZERO; }
    }

    public record ReportedBalance(BigDecimal amount, String currency, String source) {}
}
