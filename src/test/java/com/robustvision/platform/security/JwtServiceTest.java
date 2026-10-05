package com.robustvision.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {
    private static final String SECRET = "unit-test-secret-that-is-at-least-thirty-two-bytes";
    private final JwtService service = new JwtService(new ObjectMapper(), SECRET, 30);
    @Test void createsAndValidatesSignedToken() {
        String token = service.createToken(User.withUsername("alice").password("ignored").roles("VIEWER").build());
        assertThat(service.validSubject(token)).contains("alice");
        assertThat(service.validToken(token).orElseThrow().sessionId()).isNotBlank();
        assertThat(service.validSubject(token + "tampered")).isEmpty();
        assertThat(service.validSubject(token + ".")).isEmpty();
    }
    @Test void rejectsSessionlessTokensInvalidAlgorithmsExpiredAndMalformedClaims() throws Exception {
        Map<String, Object> claims = Map.of("sub", "alice", "jti", UUID.randomUUID().toString(), "exp", Instant.now().plusSeconds(60).getEpochSecond());
        assertThat(service.validToken(signed(Map.of("alg", "none", "typ", "JWT"), claims))).isEmpty();
        assertThat(service.validToken(signed(Map.of("alg", "HS256", "typ", "JWT"),
                Map.of("sub", "alice", "exp", Instant.now().plusSeconds(60).getEpochSecond())))).isEmpty();
        assertThat(service.validToken(signed(Map.of("alg", "HS256", "typ", "JWT"),
                Map.of("sub", "alice", "jti", UUID.randomUUID().toString(), "exp", Instant.now().minusSeconds(1).getEpochSecond())))).isEmpty();
        assertThat(service.validToken(signed(Map.of("alg", "HS256", "typ", "JWT"),
                Map.of("sub", "alice", "jti", "not-a-session", "exp", "9999999999")))).isEmpty();
        assertThat(service.validToken(null)).isEmpty();
        assertThat(service.validToken("malformed")).isEmpty();
    }
    private String signed(Object header, Object payload) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding(); var mapper = new ObjectMapper();
        String unsigned = encoder.encodeToString(mapper.writeValueAsBytes(header)) + "." + encoder.encodeToString(mapper.writeValueAsBytes(payload));
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return unsigned + "." + encoder.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
    }
}
