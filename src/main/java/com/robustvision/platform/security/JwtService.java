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
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expirationMinutes = expirationMinutes;
    }

    public String createToken(UserDetails userDetails) {
        Instant now = Instant.now();
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", userDetails.getUsername());
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
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return Optional.empty();
            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] actual = DECODER.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) return Optional.empty();
            JsonNode claims = objectMapper.readTree(DECODER.decode(parts[1]));
            if (!claims.hasNonNull("sub") || !claims.hasNonNull("exp")) return Optional.empty();
            if (Instant.now().getEpochSecond() >= claims.get("exp").asLong()) return Optional.empty();
            return Optional.of(claims.get("sub").asText());
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

