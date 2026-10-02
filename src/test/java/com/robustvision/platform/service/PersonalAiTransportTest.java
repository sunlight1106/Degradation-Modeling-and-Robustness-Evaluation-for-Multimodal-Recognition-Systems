package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.AiProvider;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalAiTransportTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PersonalAiEndpointPolicy policy = mock(PersonalAiEndpointPolicy.class);
    private static final String KEY = "synthetic-personal-api-key";
    private static String success(AiProvider p) {
        return switch (p.protocol()) {
            case GEMINI -> "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hidden\"},{\"text\":\"answer\"}]}}],\"usageMetadata\":{\"promptTokenCount\":12,\"candidatesTokenCount\":4}}";
            case ANTHROPIC -> "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"answer\"}],\"usage\":{\"input_tokens\":12,\"output_tokens\":4}}";
            default -> "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4}}";
        };
    }
    private PersonalAiTransport transport(Interceptor interceptor) throws Exception {
        when(policy.resolvePublic(anyString())).thenReturn(List.of(InetAddress.getByName("8.8.8.8")));
        return new PersonalAiTransport(mapper, policy) {
            @Override OkHttpClient connectionClient(String host, List<InetAddress> pinned) {
                return client(host, pinned).newBuilder().addInterceptor(interceptor).build();
            }
        };
    }
    @ParameterizedTest @EnumSource(AiProvider.class)
    void allProviderAdaptersUseCorrectBodyHeadersAndExtractUsage(AiProvider provider) throws Exception {
        AtomicReference<Request> sent = new AtomicReference<>();
        PersonalAiTransport t = transport(chain -> { sent.set(chain.request()); return response(chain.request(), 200, success(provider)); });
        String base = provider.baseUrls().isEmpty() ? "https://custom.example/v1" : provider.baseUrls().get(0);
        var payload = t.prepare(provider, base, "model-id", "system", "approved-context");
        var result = t.execute(provider, payload, KEY);
        assertThat(result.text()).isEqualTo("answer"); assertThat(result.inputTokens()).isEqualTo(12); assertThat(result.outputTokens()).isEqualTo(4);
        var json = mapper.readTree(payload.json());
        assertThat(payload.json()).doesNotContain(KEY);
        switch (provider.protocol()) {
            case GEMINI -> {
                assertThat(sent.get().header("x-goog-api-key")).isEqualTo(KEY);
                assertThat(payload.url()).endsWith("/models/model-id:generateContent");
                assertThat(json.path("systemInstruction").path("parts").path(0).path("text").asText()).isEqualTo("system");
                assertThat(json.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(1600);
            }
            case ANTHROPIC -> {
                assertThat(sent.get().header("x-api-key")).isEqualTo(KEY);
                assertThat(sent.get().header("anthropic-version")).isEqualTo("2023-06-01");
                assertThat(payload.url()).endsWith("/messages");
                assertThat(json.path("system").asText()).isEqualTo("system");
            }
            default -> {
                assertThat(sent.get().header("Authorization")).isEqualTo("Bearer " + KEY);
                assertThat(payload.url()).endsWith("/chat/completions");
                assertThat(json.path("messages").path(1).path("content").asText()).isEqualTo("approved-context");
                assertThat(json.path("stream").asBoolean()).isFalse();
            }
        }
        verify(policy).resolvePublic(sent.get().url().host());
    }
    @ParameterizedTest @EnumSource(AiProvider.class)
    void visionAdaptersEmbedOnlyProvidedImageWithCorrectProtocol(AiProvider provider) throws Exception {
        var t = new PersonalAiTransport(mapper, policy);
        byte[] image = new byte[]{1, 2, 3};
        String base = provider.baseUrls().isEmpty() ? "https://custom.example/v1" : provider.baseUrls().get(0);
        var payload = t.prepareVision(provider, base, "model-id", "system", "recognize only provided image", image, "image/png");
        var body = mapper.readTree(payload.json());
        switch (provider.protocol()) {
            case GEMINI -> {
                var data = body.path("contents").path(0).path("parts").path(1).path("inlineData");
                assertThat(data.path("mimeType").asText()).isEqualTo("image/png");
                assertThat(data.path("data").asText()).isEqualTo("AQID");
            }
            case ANTHROPIC -> {
                var source = body.path("messages").path(0).path("content").path(0).path("source");
                assertThat(source.path("media_type").asText()).isEqualTo("image/png");
                assertThat(source.path("data").asText()).isEqualTo("AQID");
                assertThat(source.path("type").asText()).isEqualTo("base64");
            }
            default -> assertThat(body.path("messages").path(1).path("content").path(1).path("image_url").path("url").asText()).isEqualTo("data:image/png;base64,AQID");
        }
        assertThatThrownBy(() -> t.prepareVision(provider, base, "model-id", "s", "c", image, "image/svg+xml"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> t.prepareVision(provider, base, "model-id", "s", "c", new byte[5 * 1024 * 1024 + 1], "image/png"))
                .isInstanceOf(BusinessException.class);
    }

    @Test void pinnedClientNeverReresolvesOrFollowsRedirectsAndHasHardTimeouts() throws Exception {
        var pinned = List.of(InetAddress.getByName("8.8.8.8"));
        var client = PersonalAiTransport.client("approved.example", pinned);
        assertThat(client.dns().lookup("approved.example")).isSameAs(pinned);
        assertThatThrownBy(() -> client.dns().lookup("attacker.example")).isInstanceOf(UnknownHostException.class);
        assertThat(client.followRedirects()).isFalse(); assertThat(client.followSslRedirects()).isFalse();
        assertThat(client.retryOnConnectionFailure()).isFalse(); assertThat(client.proxy()).isEqualTo(Proxy.NO_PROXY);
        assertThat(client.callTimeoutMillis()).isEqualTo(30000); assertThat(client.readTimeoutMillis()).isEqualTo(20000);
        var t = transport(chain -> response(chain.request(), 302, KEY));
        assertSafeFailure(t);
    }
    @Test void oversizedTimeoutMalformedErrorAndSecretEchoAreSanitized() throws Exception {
        for (String body : List.of("x".repeat(PersonalAiTransport.MAX_RESPONSE_BYTES + 1), "invalid " + KEY,
                success(AiProvider.OPENAI).replace("answer", KEY), "{\"error\":\"" + KEY + "\"}")) {
            assertSafeFailure(transport(chain -> response(chain.request(), 200, body)));
        }
        assertSafeFailure(transport(chain -> { throw new SocketTimeoutException(KEY); }));
        assertSafeFailure(transport(chain -> response(chain.request(), 401, KEY)));
    }
    @ParameterizedTest @EnumSource(AiProvider.class)
    void incompleteAndEmptyResponsesFailWithoutFallback(AiProvider provider) {
        PersonalAiTransport t = new PersonalAiTransport(mapper, policy);
        for (String json : List.of("{}", success(provider).replace("answer", ""),
                success(provider).replace("\"STOP\"", "\"MAX_TOKENS\"").replace("\"end_turn\"", "\"max_tokens\"").replace("\"stop\"", "\"length\""))) {
            assertThatThrownBy(() -> t.parse(provider, json)).isInstanceOf(BusinessException.class);
        }
    }
    private void assertSafeFailure(PersonalAiTransport transport) {
        var payload = transport.prepare(AiProvider.OPENAI, "https://api.openai.com/v1", "model-id", "system", "body");
        assertThatThrownBy(() -> transport.execute(AiProvider.OPENAI, payload, KEY)).isInstanceOf(BusinessException.class)
                .hasMessageNotContaining(KEY).hasMessageNotContaining("invalid");
    }
    private static Response response(Request request, int status, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("mock")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }
}
