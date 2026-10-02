package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.domain.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ModelTransportTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FileService files = mock(FileService.class);
    private final ProviderKeyRingService keys = mock(ProviderKeyRingService.class);
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startLocalStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopLocalStub() { server.stop(0); }

    @Test
    void reusesTransportButReadsCredentialsPerRequestDuringRotation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> headers = new ArrayList<>();
        server.createContext("/chat/completions", exchange -> {
            headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            if (calls.getAndIncrement() == 0) respond(exchange, 401, "{\"error\":\"synthetic-retry\"}");
            else respond(exchange, 200, completion());
        });
        when(keys.count(ModelProvider.DEEPSEEK)).thenReturn(2);
        when(keys.next(ModelProvider.DEEPSEEK)).thenReturn("synthetic-key-one", "synthetic-key-two", "synthetic-key-three");
        FileAssetEntity file = media(false);
        when(files.readBytes(file)).thenReturn(new byte[]{1, 2, 3});
        ModelInvocationService service = service();
        var first = service.invoke(file, model(ModelProvider.DEEPSEEK, false), TaskType.LICENSE_PLATE, false, "fixture-trace");
        var second = service.invoke(file, model(ModelProvider.DEEPSEEK, false), TaskType.LICENSE_PLATE, false, "fixture-trace-2");
        assertThat(first.confidence()).isEqualTo(0.75);
        assertThat(second.inputTokens()).isEqualTo(10);
        assertThat(headers).containsExactly("Bearer synthetic-key-one", "Bearer synthetic-key-two", "Bearer synthetic-key-three");
        assertThat(service.client(baseUrl)).isSameAs(service.client(baseUrl));
        verify(keys, times(3)).next(ModelProvider.DEEPSEEK);
    }

    @Test
    void kimiVideoUploadsRawBytesUsesFileReferenceAndCleansUpWithSameCredential() throws Exception {
        List<String> events = new ArrayList<>();
        List<JsonNode> completions = new ArrayList<>();
        List<String> uploads = new ArrayList<>();
        server.createContext("/files", exchange -> {
            events.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-video-key");
            if ("POST".equals(exchange.getRequestMethod())) {
                uploads.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, "{\"id\":\"synthetic-video-file\"}");
            } else respond(exchange, 200, "{}");
        });
        server.createContext("/chat/completions", exchange -> {
            events.add("POST /chat/completions");
            completions.add(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, completion());
        });
        FileAssetEntity file = media(true);
        when(files.readBytes(file)).thenReturn("SYNTHETIC_RAW_VIDEO_BYTES".getBytes(StandardCharsets.UTF_8));
        when(keys.count(ModelProvider.KIMI)).thenReturn(1);
        when(keys.next(ModelProvider.KIMI)).thenReturn("synthetic-video-key");
        var result = service().invoke(file, model(ModelProvider.KIMI, true), TaskType.VIDEO_ANALYSIS, true, "fixture-trace");
        assertThat(result.confidence()).isEqualTo(0.75);
        assertThat(events).containsExactly("POST /files", "POST /chat/completions", "DELETE /files/synthetic-video-file");
        assertThat(uploads.get(0)).contains("SYNTHETIC_RAW_VIDEO_BYTES");
        assertThat(completions.get(0).path("messages").get(1).path("content").get(1).path("video_url").path("url").asText())
                .isEqualTo("ms://synthetic-video-file");
        assertThat(completions.get(0).toString()).doesNotContain("base64");
    }

    @Test
    void runtimeInfoCountsEachProviderOnceWithoutRetainingCredentialResults() {
        when(keys.count(ModelProvider.DEEPSEEK)).thenReturn(2, 0);
        ModelInvocationService service = service();
        assertThat(service.runtimeInfo().credentialConfigured()).isTrue();
        assertThat(service.runtimeInfo().credentialConfigured()).isFalse();
        for (ModelProvider provider : List.of(ModelProvider.DEEPSEEK, ModelProvider.KIMI, ModelProvider.QWEN))
            verify(keys, times(2)).count(provider);
        verify(keys, never()).configured(any());
    }

    private ModelInvocationService service() {
        return new ModelInvocationService(files, mapper, keys, "live", baseUrl,
                baseUrl, "synthetic-image", baseUrl, "synthetic-kimi", baseUrl, "synthetic-qwen", "synthetic-video");
    }
    private FileAssetEntity media(boolean video) {
        return new FileAssetEntity("synthetic." + (video ? "mp4" : "png"), "unused", video ? "video/mp4" : "image/png",
                3, "0".repeat(64), "unused", null, FileSource.UPLOAD, FileScanStatus.SKIPPED, "test");
    }
    private ModelDefinitionEntity model(ModelProvider provider, boolean video) {
        return new ModelDefinitionEntity("synthetic", "Synthetic", "1", provider,
                video ? TaskType.VIDEO_ANALYSIS : TaskType.LICENSE_PLATE, "local-stub-test");
    }
    private String completion() {
        return "{\"id\":\"synthetic\",\"choices\":[{\"message\":{\"content\":\"{\\\"confidence\\\":0.75,\\\"prediction\\\":{\\\"text\\\":\\\"fixture\\\"}}\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
    }
    private void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
