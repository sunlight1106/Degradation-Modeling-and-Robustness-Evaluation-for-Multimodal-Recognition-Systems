package com.robustvision.platform.security;

import com.fasterxml.jackson.databind.*;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:admin_suite;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;TIME ZONE=UTC", "app.bootstrap.enabled=false", "app.worker.enabled=false", "app.rate-limit.enabled=false"})
@AutoConfigureMockMvc @Import(AdministrationIntegrationTest.FastPasswords.class)
class AdministrationIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired UserRepository users;
    @Autowired RoleRepository roles; @Autowired PasswordEncoder passwords; @Autowired JdbcTemplate jdbc;
    @org.springframework.boot.test.mock.mockito.MockBean com.robustvision.platform.service.LiveUpdateService live;
    UserEntity admin, member, other, delegate; RoleEntity basic; String token;
    private static final String PASSWORD = "SyntheticAdmin123!";
    @TestConfiguration static class FastPasswords { @Bean @Primary PasswordEncoder testPasswords() { return new BCryptPasswordEncoder(4); } }
    @BeforeEach void setup() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        // ADMIN is deliberately missing all stored permissions: future additions must
        // still work in method security and in the user response.
        RoleEntity highest = roles.findByCode("ADMIN").orElseGet(() -> roles.save(new RoleEntity("ADMIN", "Admin", "Test", Set.of())));
        basic = roles.save(new RoleEntity("B_" + suffix, "Member", "Test", Set.of("vocabulary:use", "message:read")));
        RoleEntity writer = roles.save(new RoleEntity("D_" + suffix, "Delegated", "Test", Set.of("user:read", "user:write", "role:write")));
        admin = create("admin" + suffix, highest); member = create("member" + suffix, basic); other = create("other" + suffix, basic); delegate = create("delegate" + suffix, writer);
        token = login(admin);
    }
    UserEntity create(String name, RoleEntity role) { return users.save(new UserEntity(name, passwords.encode(PASSWORD), "Duplicate display name", name + "@example.test", role)); }
    String login(UserEntity u) throws Exception { return data(mvc.perform(body(post("/api/v1/auth/login"), null, Map.of("username", u.getUsername(), "password", PASSWORD))).andExpect(status().isOk()).andReturn()).path("token").asText(); }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String t) { return request.header("Authorization", "Bearer " + t); }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String t, Object value) throws Exception { if (t != null) auth(request, t); return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(value)); }
    JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()).path("data"); }
    JsonNode access(UserEntity u, Set<String> grants, Set<String> denies, String expiry) throws Exception {
        Map<String, Object> request = new HashMap<>(); request.put("grants", grants); request.put("denies", denies); request.put("expiresAt", expiry);
        return data(mvc.perform(body(put("/api/v1/admin/users/{id}/access", u.getId()), token, request)).andExpect(status().isOk()).andReturn());
    }
    JsonNode stats() throws Exception { return data(mvc.perform(auth(get("/api/v1/admin/statistics").param("days", "7"), token)).andExpect(status().isOk()).andReturn()); }

    @Test void adminAlwaysGetsFullCatalogButBasicUsersCannotReadManagement() throws Exception {
        var me = data(mvc.perform(auth(get("/api/v1/auth/me"), token)).andExpect(status().isOk()).andReturn());
        Set<String> resolved = new HashSet<>(); me.path("permissions").forEach(p -> resolved.add(p.asText()));
        assertThat(resolved).isEqualTo(Permissions.allCodes());
        assertThat(stats().path("days").size()).isEqualTo(7);
        String basicToken = login(member);
        for (String path : List.of("/api/v1/admin/statistics", "/api/v1/admin/users", "/api/v1/admin/audit", "/api/v1/admin/users/" + other.getId())) mvc.perform(auth(get(path), basicToken)).andExpect(status().isForbidden());
    }
    @Test void individualDenyAndGrantApplyOnServerRevokeOldLoginAndNeverAffectOtherUsers() throws Exception {
        String previous = login(member), others = login(other);
        var changed = access(member, Set.of("contacts:use", "admin:stats"), Set.of("vocabulary:use"), null);
        assertThat(changed.path("effectivePermissions").toString()).contains("contacts:use").doesNotContain("vocabulary:use");
        mvc.perform(auth(get("/api/v1/auth/me"), previous)).andExpect(status().isUnauthorized());
        String next = login(member);
        mvc.perform(auth(get("/api/v1/vocabulary/dashboard"), next)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/social/contacts"), next)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/admin/statistics"), next)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/social/contacts"), others)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/auth/me"), others)).andExpect(status().isOk());
        access(member, Set.of(), Set.of(), null);
        assertThat(users.findById(member.getId()).orElseThrow().getPermissionOverrides()).isEmpty();
    }
    @Test void overridesCannotEscalateToAdminAndRejectInvalidOrOverlappingCodes() throws Exception {
        String delegatedToken = login(delegate);
        mvc.perform(body(put("/api/v1/admin/users/{id}/access", member.getId()), delegatedToken, Map.of("grants", Set.of("role:write"), "denies", Set.of()))).andExpect(status().isForbidden());
        mvc.perform(body(put("/api/v1/admin/users/{id}/access", admin.getId()), token, Map.of("grants", Set.of(), "denies", Set.of("user:read")))).andExpect(status().isBadRequest());
        mvc.perform(body(put("/api/v1/admin/users/{id}/access", member.getId()), token, Map.of("grants", Set.of("unknown:power"), "denies", Set.of()))).andExpect(status().isBadRequest());
        mvc.perform(body(put("/api/v1/admin/users/{id}/access", member.getId()), token, Map.of("grants", Set.of("message:read"), "denies", Set.of("message:read")))).andExpect(status().isBadRequest());
        mvc.perform(body(put("/api/v1/admin/users/{id}/access", member.getId()), token, Map.of("grants", Set.of(), "denies", Set.of(), "expiresAt", "2100-01-01T00:00:00Z"))).andExpect(status().isBadRequest());
        mvc.perform(body(put("/api/v1/admin/users/{id}/access", member.getId()), token, Map.of("grants", Set.of(), "denies", Set.of(), "expiresAt", "1960-01-01T00:00:00Z"))).andExpect(status().isBadRequest());
        access(member, Set.of("role:write"), Set.of(), null);
        mvc.perform(body(post("/api/v1/roles"), login(member), Map.of("code", "EVIL_ROLE", "name", "Escalation", "permissions", Permissions.allCodes()))).andExpect(status().isForbidden());
    }
    @Test void expiryBlocksNewLoginAndExistingSessionsAndPromotionClearsExpiry() throws Exception {
        String existing = login(member);
        jdbc.update("UPDATE app_user SET access_expires_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(1)), member.getId());
        mvc.perform(auth(get("/api/v1/auth/me"), existing)).andExpect(status().isUnauthorized());
        mvc.perform(body(post("/api/v1/auth/login"), null, Map.of("username", member.getUsername(), "password", PASSWORD))).andExpect(status().isUnauthorized());
        mvc.perform(body(patch("/api/v1/users/{id}", member.getId()), token, Map.of("roleId", admin.getRole().getId()))).andExpect(status().isOk());
        assertThat(users.findById(member.getId()).orElseThrow().getAccessExpiresAt()).isNull();
        login(member);
    }
    @Test void revokingSessionsIsTargetedAndAuditedWithoutCredentials() throws Exception {
        String a = login(member), b = login(other);
        mvc.perform(auth(post("/api/v1/admin/users/{id}/revoke-sessions", member.getId()), token)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/auth/me"), a)).andExpect(status().isUnauthorized());
        mvc.perform(auth(get("/api/v1/auth/me"), b)).andExpect(status().isOk());
        mvc.perform(auth(post("/api/v1/admin/users/{id}/revoke-sessions", admin.getId()), token)).andExpect(status().isBadRequest());
        mvc.perform(body(patch("/api/v1/users/{id}", member.getId()), token, Map.of("password", "ReplacementSecret123!"))).andExpect(status().isOk());
        var rows = data(mvc.perform(auth(get("/api/v1/admin/audit"), token)).andExpect(status().isOk()).andReturn());
        assertThat(rows.toString()).contains("USER_SESSIONS", "USER_UPDATE").doesNotContain(PASSWORD, "ReplacementSecret123!", a, b);
    }
    @Test void pagedDirectorySearchesUniqueIdentityAndEscapesWildcards() throws Exception {
        var result = data(mvc.perform(auth(get("/api/v1/admin/users").param("q", member.getIdentityCode()), token)).andExpect(status().isOk()).andReturn());
        assertThat(result.path("total").asInt()).isEqualTo(1); assertThat(result.path("items").get(0).path("id").asLong()).isEqualTo(member.getId());
        assertThat(data(mvc.perform(auth(get("/api/v1/admin/users").param("q", "%"), token)).andExpect(status().isOk()).andReturn()).path("items").size()).isZero();
        mvc.perform(auth(get("/api/v1/admin/users").param("page", "-1"), token)).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/admin/users").param("size", "5000"), token)).andExpect(status().isBadRequest());
        var detail = data(mvc.perform(auth(get("/api/v1/admin/users/{id}", member.getId()), token)).andExpect(status().isOk()).andReturn());
        assertThat(detail.toString()).doesNotContain("passwordHash", "encryptedKey", "body");
    }
    @Test void trendsFillZeroDaysAndDeduplicateSameDayLogins() throws Exception {
        var before = stats(); long logins = before.path("days").get(6).path("loginUsers").asLong();
        login(member); login(member);
        var after = stats(); assertThat(after.path("days").get(6).path("loginUsers").asLong()).isEqualTo(logins + 1);
        LocalDate start = LocalDate.now(ZoneOffset.UTC).minusDays(6);
        for (int i = 0; i < 7; i++) assertThat(after.path("days").get(i).path("date").asText()).isEqualTo(start.plusDays(i).toString());
        mvc.perform(auth(get("/api/v1/admin/statistics").param("days", "9999"), token)).andExpect(status().isBadRequest());
    }
    @Test void individuallyBlockedModulesRejectDirectRequestsEvenWithOtherWritePermissions() throws Exception {
        String restricted = login(other);
        for (String path : List.of("/api/v1/personal-ai/settings", "/api/v1/personal-ai/memories", "/api/v1/personal-ai/training/environment", "/api/v1/social/contacts", "/api/v1/workspaces", "/api/v1/research/records?kind=FLASHCARD")) mvc.perform(auth(get(path), restricted)).andExpect(status().isForbidden());
    }
    @Test void notificationStreamCompletesItsAuthorizedAsyncResponseWithoutOpeningAnonymousAccess() throws Exception {
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(1000L);
        org.mockito.Mockito.when(live.connect(member.getId())).thenReturn(emitter);
        var result = mvc.perform(auth(get("/api/v1/notifications/events"), login(member)))
                .andExpect(request().asyncStarted()).andReturn();
        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("ready").data("connected"));
        emitter.complete();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications/events")).andExpect(status().isUnauthorized());
    }
}
