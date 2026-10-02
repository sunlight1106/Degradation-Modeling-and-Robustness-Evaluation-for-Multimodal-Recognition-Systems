package com.robustvision.platform.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long expirationMinutes;

    public JwtService(ObjectMapper objectMapper,
                      @Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-minutes:120}") long expirationMinutes) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
        }
        if (expirationMinutes <= 0) {
            throw new IllegalArgumentException("JWT expiration must be a positive number of minutes");
        }
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expirationMinutes = expirationMinutes;
    }

    public record ValidToken(String subject, String sessionId) {}

    public String createToken(UserDetails userDetails) {
        return createToken(userDetails, UUID.randomUUID().toString(), Instant.now());
    }

    public String createToken(UserDetails userDetails, String sessionId, Instant now) {
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", userDetails.getUsername());
        payload.put("jti", sessionId);
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", expiresAt(now).getEpochSecond());
        payload.put("authorities", userDetails.getAuthorities().stream().map(Object::toString).toList());
        try {
            String encodedHeader = encode(objectMapper.writeValueAsBytes(header));
            String encodedPayload = encode(objectMapper.writeValueAsBytes(payload));
            String unsigned = encodedHeader + "." + encodedPayload;
            return unsigned + "." + encode(sign(unsigned));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法创建访问令牌", exception);
        }
    }

    public Optional<String> validSubject(String token) {
        return validToken(token).map(ValidToken::subject);
    }

    public Optional<ValidToken> validToken(String token) {
        try {
            if (token == null || token.length() > 8192) return Optional.empty();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) return Optional.empty();
            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] actual = DECODER.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) return Optional.empty();
            JsonNode header = objectMapper.readTree(DECODER.decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText()) || !"JWT".equals(header.path("typ").asText())) return Optional.empty();
            JsonNode claims = objectMapper.readTree(DECODER.decode(parts[1]));
            if (!claims.path("sub").isTextual() || claims.path("sub").asText().isBlank()
                    || !claims.path("jti").isTextual() || !claims.path("exp").isIntegralNumber()) return Optional.empty();
            String sessionId = claims.get("jti").asText();
            if (!UUID.fromString(sessionId).toString().equals(sessionId)) return Optional.empty();
            if (Instant.now().getEpochSecond() >= claims.get("exp").asLong()) return Optional.empty();
            return Optional.of(new ValidToken(claims.get("sub").asText(), sessionId));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public Instant expiresAt(Instant issuedAt) {
        return issuedAt.plus(expirationMinutes, ChronoUnit.MINUTES);
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("无法签名访问令牌", exception);
        }
    }

    private String encode(byte[] value) {
        return ENCODER.encodeToString(value);
    }
}

