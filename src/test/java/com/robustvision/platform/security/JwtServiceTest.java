package com.robustvision.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {
    @Test
    void createsAndValidatesSignedToken() {
        JwtService service = new JwtService(new ObjectMapper(),
                "unit-test-secret-that-is-at-least-thirty-two-bytes", 30);
        String token = service.createToken(User.withUsername("alice").password("ignored").roles("VIEWER").build());
        assertThat(service.validSubject(token)).contains("alice");
        assertThat(service.validSubject(token + "tampered")).isEmpty();
    }
}

