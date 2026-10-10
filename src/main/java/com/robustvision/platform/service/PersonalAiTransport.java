package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.AiProvider;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Synchronous, bounded transport with DNS pinning, TLS hostname checks, no proxy, retries or redirects. */
@Component
public class PersonalAiTransport {
    static final int MAX_RESPONSE_BYTES = 262144;
    static final int MAX_OUTPUT_CHARS = 24000;
    private final ObjectMapper mapper;
    private final PersonalAiEndpointPolicy policy;
    public PersonalAiTransport(ObjectMapper mapper, PersonalAiEndpointPolicy policy) { this.mapper = mapper; this.policy = policy; }
    public record Payload(String url, String json) {
        @Override public String toString() { return "Payload[REDACTED]"; }
    }
    public record Completion(String text, Long inputTokens, Long outputTokens) {
        public Completion(String text,long inputTokens,long outputTokens) { this(text,Long.valueOf(inputTokens),Long.valueOf(outputTokens)); }
    }

    public Payload prepare(AiProvider provider, String base, String model, String system, String context) {
        policy.validateBase(provider, base);
        Map<String, Object> body = new LinkedHashMap<>();
        String url;
        switch (provider.protocol()) {
            case GEMINI -> {
                url = base + "/models/" + model + ":generateContent";
                body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
                body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", context)))));
                body.put("generationConfig", Map.of("maxOutputTokens", 1600));
            }
            case ANTHROPIC -> {
                url = base + "/messages";
                body.put("model", model); body.put("max_tokens", 1600); body.put("stream", false);
                body.put("system", system);
                body.put("messages", List.of(Map.of("role", "user", "content", context)));
            }
            default -> {
                url = base + "/chat/completions";
                body.put("model", model); body.put("stream", false);
                body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", context)));
                boolean modern = provider == AiProvider.OPENAI || provider == AiProvider.MOONSHOT
                        || provider == AiProvider.QWEN || base.startsWith("https://api.groq.com/");
                body.put(modern ? "max_completion_tokens" : "max_tokens", 1600);
                if (provider == AiProvider.QWEN) body.put("enable_thinking", false);
            }
        }
        try { return new Payload(url, mapper.writeValueAsString(body)); }
        catch (Exception exception) { throw PersonalAiEndpointPolicy.unavailable(); }
    }

    /** Image content is embedded; the server never fetches a user-controlled image URL. */
    public Payload prepareVision(AiProvider provider, String base, String model, String system, String context,
                                 byte[] image, String mime) {
        if (image == null || image.length == 0 || image.length > 5 * 1024 * 1024
                || !Set.of("image/png", "image/jpeg", "image/webp").contains(mime)) {
            throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "PERSONAL_AI_IMAGE_INVALID", "仅支持不超过 5 MiB 的 PNG、JPEG 或 WebP 图片");
        }
        Payload text = prepare(provider, base, model, system, context);
        String encoded = Base64.getEncoder().encodeToString(image);
        try {
            ObjectNode body = (ObjectNode) mapper.readTree(text.json());
            switch (provider.protocol()) {
                case GEMINI -> body.set("contents", mapper.valueToTree(List.of(Map.of("role", "user", "parts", List.of(
                        Map.of("text", context), Map.of("inlineData", Map.of("mimeType", mime, "data", encoded)))))));
                case ANTHROPIC -> body.set("messages", mapper.valueToTree(List.of(Map.of("role", "user", "content", List.of(
                        Map.of("type", "image", "source", Map.of("type", "base64", "media_type", mime, "data", encoded)),
                        Map.of("type", "text", "text", context))))));
                default -> body.set("messages", mapper.valueToTree(List.of(Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", List.of(Map.of("type", "text", "text", context),
                                Map.of("type", "image_url", "image_url", Map.of("url", "data:" + mime + ";base64," + encoded)))))));
            }
            return new Payload(text.url(), mapper.writeValueAsString(body));
        } catch (Exception exception) { throw PersonalAiEndpointPolicy.unavailable(); }
    }

    public Completion execute(AiProvider provider, Payload payload, String apiKey) {
        String suffix = switch (provider.protocol()) {
            case ANTHROPIC -> "/messages";
            case OPENAI_CHAT -> "/chat/completions";
            case GEMINI -> {
                int at = payload.url().lastIndexOf("/models/");
                if (at < 0 || !payload.url().substring(at).matches("/models/[A-Za-z0-9._-]+:generateContent")) throw PersonalAiEndpointPolicy.invalid();
                yield payload.url().substring(at);
            }
        };
        if (!payload.url().endsWith(suffix)) throw PersonalAiEndpointPolicy.invalid();
        policy.validateBase(provider, payload.url().substring(0, payload.url().length() - suffix.length()));
        HttpUrl url = HttpUrl.get(payload.url());
        // Resolution happens once. OkHttp receives only this vetted snapshot, so DNS changes cannot redirect the socket.
        List<InetAddress> pinned = policy.resolvePublic(url.host());
        OkHttpClient client = connectionClient(url.host(), pinned);
        Request.Builder request = new Request.Builder().url(url).post(RequestBody.create(payload.json(), MediaType.get("application/json; charset=utf-8")));
        switch (provider.protocol()) {
            case GEMINI -> request.header("x-goog-api-key", apiKey);
            case ANTHROPIC -> request.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
            default -> request.header("Authorization", "Bearer " + apiKey);
        }
        try (Response response = client.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) throw upstreamFailure(response.code());
            if (response.body() == null) throw PersonalAiEndpointPolicy.unavailable();
            Completion result = parse(provider, readBounded(response.body()));
            if (result.text().contains(apiKey)) throw new PersonalAiUpstreamFailure(result.inputTokens(), result.outputTokens());
            return result;
        } catch (BusinessException exception) { throw exception; }
        catch (java.net.SocketTimeoutException exception) { throw new BusinessException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,"PERSONAL_AI_TIMEOUT","供应商响应超时，请稍后再试"); }
        catch (Exception exception) { throw PersonalAiEndpointPolicy.unavailable(); }
        finally { client.connectionPool().evictAll(); }
    }
    public Payload prepareDiagnostic(AiProvider provider,String base,String model) {
        Payload original=prepare(provider,base,model,"Reply briefly.","Reply with OK only.");
        try {var body=(ObjectNode)mapper.readTree(original.json());
            if(provider.protocol()==AiProvider.Protocol.GEMINI)((ObjectNode)body.get("generationConfig")).put("maxOutputTokens",128);
            else body.put(body.has("max_completion_tokens")?"max_completion_tokens":"max_tokens",128);
            return new Payload(original.url(),mapper.writeValueAsString(body));
        } catch(Exception e){throw PersonalAiEndpointPolicy.unavailable();}
    }
    public List<String> models(AiProvider provider,String base,String apiKey) {
        policy.validateBase(provider,base);HttpUrl url=HttpUrl.get(base+"/models");
        List<InetAddress> pinned=policy.resolvePublic(url.host());OkHttpClient client=connectionClient(url.host(),pinned);
        Request.Builder request=new Request.Builder().url(url).get();
        switch(provider.protocol()){case GEMINI->request.header("x-goog-api-key",apiKey);case ANTHROPIC->request.header("x-api-key",apiKey).header("anthropic-version","2023-06-01");default->request.header("Authorization","Bearer "+apiKey);}
        try(Response response=client.newCall(request.build()).execute()){
            if(!response.isSuccessful())throw upstreamFailure(response.code());if(response.body()==null)throw PersonalAiEndpointPolicy.unavailable();
            var json=mapper.readTree(readBounded(response.body()));var entries=json.path(provider.protocol()==AiProvider.Protocol.GEMINI?"models":"data");if(!entries.isArray())throw PersonalAiEndpointPolicy.unavailable();
            List<String> ids=new ArrayList<>();for(var entry:entries){String id=entry.path(provider.protocol()==AiProvider.Protocol.GEMINI?"name":"id").asText().replaceFirst("^models/","");if(id.length()<=120&&id.matches("[A-Za-z0-9._:/-]+")&&!id.contains(apiKey))ids.add(id);if(ids.size()==200)break;}return ids;
        }catch(BusinessException e){throw e;}catch(Exception e){throw PersonalAiEndpointPolicy.unavailable();}finally{client.connectionPool().evictAll();}
    }
    static BusinessException upstreamFailure(int status){
        String code,message;switch(status){
            case 401,403->{code="PERSONAL_AI_AUTH_FAILED";message="密钥无效或没有调用权限，请检查自己的供应商配置";}
            case 402->{code="PERSONAL_AI_QUOTA";message="供应商额度不足，请查看供应商账单";}
            case 404->{code="PERSONAL_AI_MODEL_NOT_FOUND";message="模型或接口不可用，请核对模型 ID 和 API 端点";}
            case 429->{code="PERSONAL_AI_RATE_OR_QUOTA";message="供应商限流或额度不足，请查看账户额度并稍后重试";}
            case 400,422->{code="PERSONAL_AI_PROTOCOL";message="供应商不接受当前模型参数，请核对模型与接口兼容性";}
            default->{code="PERSONAL_AI_UPSTREAM_UNAVAILABLE";message="供应商服务或网络暂不可用，请稍后重试";}
        }return new BusinessException(org.springframework.http.HttpStatus.BAD_GATEWAY,code,message);
    }
    OkHttpClient connectionClient(String host, List<InetAddress> pinned) { return client(host, pinned); }
    static OkHttpClient client(String host, List<InetAddress> pinned) {
        return new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .dns(requested -> { if (!host.equals(requested)) throw new UnknownHostException("Endpoint mismatch"); return pinned; })
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(Duration.ofSeconds(8)).readTimeout(Duration.ofSeconds(20)).writeTimeout(Duration.ofSeconds(10))
                .callTimeout(Duration.ofSeconds(30)).connectionPool(new ConnectionPool()).build();
    }
    static String readBounded(ResponseBody body) throws Exception {
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw PersonalAiEndpointPolicy.unavailable();
        try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) throw PersonalAiEndpointPolicy.unavailable();
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }
    Completion parse(AiProvider provider, String json) {
        Long input = null, output = null;
        try {
            JsonNode root = mapper.readTree(json);
            String text;
            switch (provider.protocol()) {
                case GEMINI -> {
                    input = tokenCount(root.path("usageMetadata").path("promptTokenCount"));
                    output = addOptional(tokenCount(root.path("usageMetadata").path("candidatesTokenCount")),
                            root.path("usageMetadata").path("thoughtsTokenCount"));
                    JsonNode candidate = root.path("candidates").path(0);
                    if (root.path("promptFeedback").hasNonNull("blockReason") || !"STOP".equals(candidate.path("finishReason").asText())) throw PersonalAiEndpointPolicy.unavailable();
                    StringBuilder content = new StringBuilder();
                    for (JsonNode part : candidate.path("content").path("parts")) if (!part.path("thought").asBoolean(false) && part.path("text").isTextual()) content.append(part.path("text").asText());
                    text = content.toString();
                }
                case ANTHROPIC -> {
                    input = addOptional(tokenCount(root.path("usage").path("input_tokens")),
                            root.path("usage").path("cache_read_input_tokens"), root.path("usage").path("cache_creation_input_tokens"));
                    output = tokenCount(root.path("usage").path("output_tokens"));
                    if (!"end_turn".equals(root.path("stop_reason").asText())) throw PersonalAiEndpointPolicy.unavailable();
                    StringBuilder content = new StringBuilder();
                    for (JsonNode block : root.path("content")) if ("text".equals(block.path("type").asText())) content.append(block.path("text").asText());
                    text = content.toString();
                }
                default -> {
                    input = tokenCount(root.path("usage").path("prompt_tokens"));
                    output = tokenCount(root.path("usage").path("completion_tokens"));
                    JsonNode choice = root.path("choices").path(0);
                    if (!"stop".equals(choice.path("finish_reason").asText()) || choice.path("message").hasNonNull("refusal")) throw PersonalAiEndpointPolicy.unavailable();
                    text = choice.path("message").path("content").asText("");
                }
            }
            if (text.isBlank() || text.length() > MAX_OUTPUT_CHARS) throw PersonalAiEndpointPolicy.unavailable();
            return new Completion(text.trim(), input, output);
        } catch (Exception exception) { throw new PersonalAiUpstreamFailure(input, output); }
    }
    private static Long addOptional(Long base, JsonNode... extras) {
        if (base == null) return null;
        long total = base;
        for (JsonNode extra : extras) {
            if (extra.isMissingNode()) continue;
            Long count = tokenCount(extra);
            if (count == null || total > 9_007_199_254_740_991L - count) return null;
            total += count;
        }
        return total;
    }
    private static Long tokenCount(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0 && value.asLong() <= 9_007_199_254_740_991L ? value.asLong() : null;
    }
}
