package com.robustvision.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:account_security;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
@Import(AccountSecurityIntegrationTest.FastPasswords.class)
class AccountSecurityIntegrationTest {
    private static final String PASSWORD = "SyntheticPassword123!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired NoteRepository notes;
    @Autowired PersonalAiSettingRepository aiSettings;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private UserEntity alice;
    private UserEntity bob;
    private UserEntity admin;
    private UserEntity delegated;
    private RoleEntity viewer;
    private String suffix;

    @BeforeEach
    void fixtures() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        viewer = roles.save(new RoleEntity("VIEWER_" + suffix, "Viewer", "Test", Set.of()));
        RoleEntity adminRole = roles.findByCode("ADMIN").orElseGet(() -> roles.save(
                new RoleEntity("ADMIN", "Admin", "Test", Permissions.allCodes())));
        RoleEntity writerRole = roles.save(new RoleEntity("WRITER_" + suffix, "Writer", "Test", Set.of("user:write", "role:write", "user:read", "role:read")));
        alice = create("alice", viewer); bob = create("bob", viewer);
        admin = create("admin", adminRole); delegated = create("delegated", writerRole);
    }
    private UserEntity create(String prefix, RoleEntity role) {
        String username = prefix + suffix;
        return users.save(new UserEntity(username, encoder.encode(PASSWORD), prefix, username + "@example.test", role));
    }
    private String login(UserEntity user) throws Exception { return login(user, PASSWORD); }
    private String login(UserEntity user, String password) throws Exception {
        return body(mvc.perform(json(post("/api/v1/auth/login"), null,
                        Map.of("username", user.getUsername(), "password", password)).header("User-Agent", "Synthetic integration browser"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("token").asText();
    }
    private JsonNode body(byte[] value) throws Exception { return mapper.readTree(value).path("data"); }
    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, Object value) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(value));
    }
    private JsonNode sessionList(String token) throws Exception {
        return body(mvc.perform(get("/api/v1/account/sessions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }
    private void active(String token) throws Exception {
        mvc.perform(get("/api/v1/account/profile").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }
    private void revoked(String token) throws Exception {
        mvc.perform(get("/api/v1/account/profile").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test void accountSwitchVerifiesTargetRevokesOnlySourceSessionAndDropsAdminPermissions() throws Exception {
        String source = login(admin), otherSource = login(admin), existingTarget = login(bob);
        JsonNode result = body(mvc.perform(json(post("/api/v1/account/switch"), source,
                Map.of("username", bob.getUsername(), "password", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.id").value(bob.getId()))
                .andReturn().getResponse().getContentAsByteArray());
        String switched = result.path("token").asText();
        revoked(source); active(otherSource); active(existingTarget); active(switched);
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + switched)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/account/switch"), source, Map.of("username", alice.getUsername(), "password", PASSWORD))).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/v1/account/switch"), null, Map.of("username", bob.getUsername(), "password", PASSWORD))).andExpect(status().isUnauthorized());
    }

    @Test void failedSwitchRollsBackRevocationAndDoesNotCreateTargetSessions() throws Exception {
        String source = login(alice);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM user_session WHERE user_id = ?", Long.class, bob.getId());
        mvc.perform(json(post("/api/v1/account/switch"), source, Map.of("username", bob.getUsername(), "password", "WrongPassword123!")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCOUNT_SWITCH_FAILED"));
        active(source);
        mvc.perform(json(post("/api/v1/account/switch"), source, Map.of("username", "missing" + suffix, "password", PASSWORD))).andExpect(status().isForbidden());
        active(source);
        mvc.perform(json(post("/api/v1/account/switch"), source, Map.of("username", alice.getUsername(), "password", PASSWORD))).andExpect(status().isBadRequest());
        active(source);
        bob.setStatus(UserStatus.DISABLED); users.save(bob);
        mvc.perform(json(post("/api/v1/account/switch"), source, Map.of("username", bob.getUsername(), "password", PASSWORD))).andExpect(status().isForbidden());
        active(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_session WHERE user_id = ?", Long.class, bob.getId())).isEqualTo(before);
    }

    @Test void profilesAreSelfScopedAndEmailChangesRequireCurrentPassword() throws Exception {
        String token = login(alice);
        mvc.perform(json(patch("/api/v1/account/profile"), token,
                Map.of("displayName", "  Updated Alice  ", "username", bob.getUsername(), "roleId", admin.getRole().getId(), "identityCode", bob.getIdentityCode())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value("Updated Alice"))
                .andExpect(jsonPath("$.data.username").value(alice.getUsername()))
                .andExpect(jsonPath("$.data.roleCode").value(viewer.getCode()))
                .andExpect(jsonPath("$.data.identityCode").value(alice.getIdentityCode()));
        String email = "changed" + suffix + "@example.test";
        mvc.perform(json(patch("/api/v1/account/profile"), token, Map.of("email", email)))
                .andExpect(status().isForbidden());
        mvc.perform(json(patch("/api/v1/account/profile"), token, Map.of("email", email, "currentPassword", "incorrect")))
                .andExpect(status().isForbidden());
        mvc.perform(json(patch("/api/v1/account/profile"), token, Map.of("email", email, "currentPassword", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.identityCode").value(alice.getIdentityCode()));
        mvc.perform(json(patch("/api/v1/account/profile"), token, Map.of("email", bob.getEmail(), "currentPassword", PASSWORD)))
                .andExpect(status().isConflict());
        mvc.perform(json(patch("/api/v1/account/profile"), token, Map.of("displayName", "    ")))
                .andExpect(status().isBadRequest());
        assertThat(users.findById(bob.getId()).orElseThrow().getDisplayName()).isEqualTo("bob");
    }
    @Test void registrationAndAdminCreationAssignDistinctPermanentCodes() throws Exception {
        roles.findByCode("RESEARCHER").orElseGet(() -> roles.save(new RoleEntity("RESEARCHER", "Researcher", "Synthetic", Set.of())));
        String username = "identity" + suffix;
        JsonNode registered = mapper.readTree(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("username", username, "email", username + "@example.test", "password", PASSWORD, "identityCode", alice.getIdentityCode()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
        String code = registered.path("identityCode").asText();
        assertThat(code).matches("PKB-[0-9A-F]{32}").isNotEqualTo(alice.getIdentityCode());
        UserEntity registeredUser = users.findByUsername(username).orElseThrow();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + login(registeredUser)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.identityCode").value(code));
        String adminToken = login(admin);
        JsonNode created = mapper.readTree(mvc.perform(json(post("/api/v1/users"), adminToken,
                Map.of("username", "created" + suffix, "displayName", "Created", "email", "created" + suffix + "@example.test", "password", PASSWORD, "roleId", viewer.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
        String createdCode = created.path("identityCode").asText();
        assertThat(createdCode).matches("PKB-[0-9A-F]{32}").isNotEqualTo(code);
        mvc.perform(json(patch("/api/v1/users/{id}", created.path("id").asLong()), adminToken,
                Map.of("roleId", registeredUser.getRole().getId(), "identityCode", code)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.identityCode").value(createdCode));
        assertThat(users.findById(registeredUser.getId()).orElseThrow().getIdentityCode()).isEqualTo(code);
    }

    @Test void forgedCrossUserSessionDeletionIsDeniedAndCurrentSessionIsMarked() throws Exception {
        String token = login(alice); String bobToken = login(bob);
        JsonNode own = sessionList(token); JsonNode other = sessionList(bobToken);
        assertThat(own.size()).isEqualTo(1);
        assertThat(own.get(0).path("current").asBoolean()).isTrue();
        assertThat(own.get(0).path("userAgent").asText()).isEqualTo("Synthetic integration browser");
        assertThat(own.get(0).path("lastSeenAt").asText()).isNotBlank();
        mvc.perform(json(delete("/api/v1/account/sessions/{id}", other.get(0).path("id").asText()), token,
                Map.of("currentPassword", PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(json(delete("/api/v1/account/sessions/{id}", own.get(0).path("id").asText()), token,
                Map.of("currentPassword", "incorrect"))).andExpect(status().isForbidden());
        active(bobToken); active(token);
        mvc.perform(json(delete("/api/v1/account/sessions/{id}", own.get(0).path("id").asText()), token,
                Map.of("currentPassword", PASSWORD))).andExpect(status().isOk());
        revoked(token); active(bobToken);
    }

    @Test void revokeOthersRetainsCurrentAndLogoutPreventsReplay() throws Exception {
        String old = login(alice); String current = login(alice);
        mvc.perform(json(post("/api/v1/account/sessions/revoke-others"), current,
                Map.of("currentPassword", "incorrect"))).andExpect(status().isForbidden());
        active(old);
        mvc.perform(json(post("/api/v1/account/sessions/revoke-others"), current,
                Map.of("currentPassword", PASSWORD))).andExpect(status().isOk());
        revoked(old); active(current); assertThat(sessionList(current).size()).isEqualTo(1);
        mvc.perform(post("/api/v1/account/logout").header("Authorization", "Bearer " + current)).andExpect(status().isOk());
        revoked(current);
    }

    @Test void passwordChangeRevokesAllSessionsOldPasswordFailsAndNewLoginWorks() throws Exception {
        String one = login(alice); String two = login(alice); String bobToken = login(bob);
        mvc.perform(json(post("/api/v1/account/password"), one,
                Map.of("currentPassword", "incorrect", "newPassword", "NewSyntheticPassword456!"))).andExpect(status().isForbidden());
        active(one);
        mvc.perform(json(post("/api/v1/account/password"), one,
                Map.of("currentPassword", PASSWORD, "newPassword", "weakword"))).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/account/password"), one,
                Map.of("currentPassword", PASSWORD, "newPassword", "NewSyntheticPassword456!"))).andExpect(status().isOk());
        revoked(one); revoked(two); active(bobToken);
        mvc.perform(json(post("/api/v1/auth/login"), null,
                Map.of("username", alice.getUsername(), "password", PASSWORD))).andExpect(status().isUnauthorized());
        active(login(alice, "NewSyntheticPassword456!"));
    }

    @Test void delegatedWritersCannotGrantRolesResetPasswordsOrEscalatePermissions() throws Exception {
        String writer = login(delegated); String bobToken = login(bob);
        mvc.perform(json(patch("/api/v1/users/{id}", bob.getId()), writer, Map.of("displayName", "Safe display change")))
                .andExpect(status().isOk());
        mvc.perform(json(patch("/api/v1/users/{id}", delegated.getId()), writer, Map.of("roleId", admin.getRole().getId())))
                .andExpect(status().isForbidden());
        mvc.perform(json(patch("/api/v1/users/{id}", bob.getId()), writer, Map.of("roleId", delegated.getRole().getId())))
                .andExpect(status().isForbidden());
        mvc.perform(json(patch("/api/v1/users/{id}", admin.getId()), writer, Map.of("password", "ResetSynthetic123!")))
                .andExpect(status().isForbidden());
        mvc.perform(json(patch("/api/v1/users/{id}", bob.getId()), writer, Map.of("password", "ResetSynthetic123!")))
                .andExpect(status().isForbidden());
        mvc.perform(json(patch("/api/v1/users/{id}", admin.getId()), writer, Map.of("status", "DISABLED")))
                .andExpect(status().isForbidden());
        mvc.perform(json(put("/api/v1/roles/{id}/permissions", delegated.getRole().getId()), writer,
                Map.of("permissions", Permissions.allCodes()))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/users"), writer, Map.of("username", "forged" + suffix, "displayName", "Forged", "email", "forged" + suffix + "@example.test", "password", PASSWORD, "roleId", admin.getRole().getId())))
                .andExpect(status().isForbidden());
        active(bobToken);
    }

    @Test void administratorResetAndStatusChangeRevokePreviousSessions() throws Exception {
        String adminToken = login(admin); String userToken = login(bob);
        mvc.perform(json(patch("/api/v1/users/{id}", bob.getId()), adminToken, Map.of("password", "ResetSynthetic123!")))
                .andExpect(status().isOk());
        revoked(userToken);
        String refreshed = login(bob, "ResetSynthetic123!");
        mvc.perform(json(patch("/api/v1/users/{id}", bob.getId()), adminToken, Map.of("status", "DISABLED")))
                .andExpect(status().isOk());
        revoked(refreshed);
        mvc.perform(json(patch("/api/v1/users/{id}", bob.getId()), adminToken, Map.of("status", "ACTIVE")))
                .andExpect(status().isOk());
        revoked(refreshed); active(login(bob, "ResetSynthetic123!"));
    }

    @Test void malformedAndOversizedPasswordsReturnSafeErrors() throws Exception {
        String token = login(alice);
        mvc.perform(json(post("/api/v1/auth/login"), null,
                Map.of("username", alice.getUsername(), "password", "密".repeat(40))))
                .andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/v1/account/password"), token,
                Map.of("currentPassword", "密".repeat(40), "newPassword", "ValidSynthetic123!")))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/account/password"), token,
                Map.of("currentPassword", PASSWORD, "newPassword", "密".repeat(30) + "A1")))
                .andExpect(status().isBadRequest());
        var malformed = mvc.perform(post("/api/v1/account/password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"SYNTHETIC_MALFORMED_MARKER\",bad"))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(malformed.getResponse().getContentAsString()).doesNotContain("SYNTHETIC_MALFORMED_MARKER");
        active(token);
    }

    @Test void administratorRoleChangesAndPermissionUpdatesRevokeSessions() throws Exception {
        String adminToken = login(admin); String userToken = login(bob);
        mvc.perform(json(patch("/api/v1/users/{id}", bob.getId()), adminToken,
                Map.of("roleId", delegated.getRole().getId()))).andExpect(status().isOk());
        revoked(userToken); String next = login(bob);
        mvc.perform(json(put("/api/v1/roles/{id}/permissions", delegated.getRole().getId()), adminToken,
                Map.of("permissions", Set.of("user:read")))).andExpect(status().isOk());
        revoked(next); active(login(bob)); active(adminToken);
    }

    @Test void exportContainsOnlyOwnedDataAndExplicitlyExcludesSecrets() throws Exception {
        notes.save(new NoteEntity(alice, "Alice private", "Own note body", "own", NoteStatus.DRAFT));
        notes.save(new NoteEntity(bob, "Bob private", "OTHER_ACCOUNT_MARKER", "other", NoteStatus.DRAFT));
        var setting = new PersonalAiSettingEntity(alice.getId(), AiProvider.OPENAI);
        setting.update("synthetic-model", "https://api.example.test", "CIPHERTEXT_MUST_NOT_APPEAR", true); aiSettings.save(setting);
        String ownBook = UUID.randomUUID().toString(), otherBook = UUID.randomUUID().toString();
        String ownWord = UUID.randomUUID().toString(), otherWord = UUID.randomUUID().toString();
        for (Object[] row : java.util.List.of(new Object[]{ownBook, alice.getId(), "Own words"}, new Object[]{otherBook, bob.getId(), "OTHER_VOCAB_BOOK_MARKER"})) {
            jdbc.update("INSERT INTO vocabulary_book (id, owner_id, title, description, attribution, level, created_at) VALUES (?, ?, ?, '', '', 'A1', CURRENT_TIMESTAMP)", row);
        }
        jdbc.update("INSERT INTO vocabulary_word (id, book_id, term, ipa, pos, meaning, example_text, example_translation, distractors, sort_order) VALUES (?, ?, 'apple', '', 'noun', '苹果', '', '', '[]', 0)", ownWord, ownBook);
        jdbc.update("INSERT INTO vocabulary_word (id, book_id, term, ipa, pos, meaning, example_text, example_translation, distractors, sort_order) VALUES (?, ?, 'OTHER_VOCAB_WORD_MARKER', '', 'noun', 'other', '', '', '[]', 0)", otherWord, otherBook);
        jdbc.update("INSERT INTO vocabulary_profile (owner_id, zone_id, daily_goal, selected_book_id, updated_at) VALUES (?, 'UTC', 10, ?, CURRENT_TIMESTAMP)", alice.getId(), ownBook);
        jdbc.update("INSERT INTO vocabulary_progress (id, owner_id, word_id, term_key, learning_correct, review_stage, wrong_count, mistake, starred) VALUES (?, ?, ?, 'apple', 1, 0, 0, false, true)", UUID.randomUUID().toString(), alice.getId(), ownWord);
        jdbc.update("INSERT INTO vocabulary_question (id, owner_id, word_id, book_id, mode, options_json, correct_option_id, created_at, expires_at) VALUES (?, ?, ?, ?, 'LEARN', '[]', 'ANSWER_SNAPSHOT_MARKER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), alice.getId(), ownWord, ownBook);
        String token = login(alice);
        mvc.perform(json(post("/api/v1/account/export"), token, Map.of("currentPassword", "incorrect")))
                .andExpect(status().isForbidden());
        var result = mvc.perform(json(post("/api/v1/account/export"), token, Map.of("currentPassword", PASSWORD, "ownerId", bob.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.profile.id").value(alice.getId()))
                .andExpect(jsonPath("$.data.notes[0].title").value("Alice private")).andReturn();
        String content = result.getResponse().getContentAsString();
        assertThat(content).doesNotContain("OTHER_ACCOUNT_MARKER", "CIPHERTEXT_MUST_NOT_APPEAR", "passwordHash", "encrypted_key", "storage_path", "OTHER_VOCAB_BOOK_MARKER", "OTHER_VOCAB_WORD_MARKER", "ANSWER_SNAPSHOT_MARKER", token);
        JsonNode exported = body(result.getResponse().getContentAsByteArray());
        assertThat(exported.path("aiSettings").size()).isEqualTo(1);
        assertThat(exported.path("vocabularyBooks").size()).isEqualTo(1);
        assertThat(exported.path("vocabularyWords").get(0).path("term").asText()).isEqualTo("apple");
        assertThat(exported.path("vocabularyProgress").size()).isEqualTo(1);
        assertThat(exported.has("vocabularyQuestions")).isFalse();
    }

    @Test void unpersistedForgedAndExpiredSessionTokensFailClosed() throws Exception {
        var principal = User.withUsername(alice.getUsername()).password("ignored").roles("ADMIN").build();
        revoked(jwt.createToken(principal));
        String valid = login(alice);
        revoked(valid + "tampered");
        mvc.perform(get("/api/v1/account/profile")).andExpect(status().isUnauthorized());
        String session = sessionList(valid).get(0).path("id").asText();
        revoked(jwt.createToken(User.withUsername(bob.getUsername()).password("ignored").roles("ADMIN").build(), session, java.time.Instant.now()));
        revoked(jwt.createToken(principal, session, java.time.Instant.now().minusSeconds(24 * 3600)));
        active(valid);
        String forgedAuthority = jwt.createToken(principal, session, java.time.Instant.now());
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + forgedAuthority)).andExpect(status().isForbidden());
    }

    @TestConfiguration static class FastPasswords {
        @Bean @Primary PasswordEncoder testPasswordEncoder() { return new BCryptPasswordEncoder(4); }
    }
}
