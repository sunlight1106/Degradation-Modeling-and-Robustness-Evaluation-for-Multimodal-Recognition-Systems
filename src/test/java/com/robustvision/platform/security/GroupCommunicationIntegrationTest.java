package com.robustvision.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.*;
import com.robustvision.platform.service.AntivirusService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:group_security;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.worker.enabled=false", "app.rate-limit.enabled=false"})
@AutoConfigureMockMvc
@Import(GroupCommunicationIntegrationTest.FastPasswords.class)
class GroupCommunicationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired MessageRepository messages;
    @Autowired MessageAttachmentRepository attachments;
    @Autowired PasswordEncoder encoder;
    @MockBean AntivirusService scanner;
    UserEntity owner, member, outsider, admin;
    String ownerToken, memberToken, outsiderToken, adminToken;
    static final String PASSWORD = "SyntheticGroup123!";

    @BeforeEach void setup() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RoleEntity research = roles.save(new RoleEntity("R_" + suffix, "Researcher", "Synthetic", Set.of("workspace:manage")));
        RoleEntity basic = roles.save(new RoleEntity("B_" + suffix, "Basic", "Synthetic", Set.of("file:read")));
        RoleEntity administrator = roles.findByCode("ADMIN").orElseGet(() -> roles.save(new RoleEntity("ADMIN", "Admin", "Synthetic", Permissions.allCodes())));
        owner = user("owner" + suffix, research); member = user("member" + suffix, basic);
        outsider = user("outsider" + suffix, basic); admin = user("admin" + suffix, administrator);
        ownerToken = login(owner); memberToken = login(member); outsiderToken = login(outsider); adminToken = login(admin);
        when(scanner.scan(any(byte[].class))).thenReturn(new AntivirusService.ScanResult(FileScanStatus.CLEAN, "synthetic scanner"));
    }
    UserEntity user(String name, RoleEntity role) { return users.save(new UserEntity(name, encoder.encode(PASSWORD), name, name + "@example.test", role)); }
    JsonNode data(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()).path("data"); }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) { return request.header("Authorization", "Bearer " + token); }
    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, Object body) throws Exception { return auth(request, token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)); }
    String login(UserEntity user) throws Exception { return data(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("username", user.getUsername(), "password", PASSWORD)))).andExpect(status().isOk()).andReturn()).path("token").asText(); }
    long group(String token) throws Exception { return data(mvc.perform(json(post("/api/v1/workspaces"), token, Map.of("name", "Synthetic group " + UUID.randomUUID(), "color", "#57788d"))).andExpect(status().isOk()).andReturn()).path("id").asLong(); }
    ResultActions membership(long group, String token, UserEntity user, String role, Set<String> permissions) throws Exception { return mvc.perform(json(put("/api/v1/workspaces/{id}/members", group), token, Map.of("userId", user.getId(), "role", role, "permissions", permissions))); }
    ResultActions postGroup(long id, String token, String text, String replyTo, boolean file) throws Exception {
        var request = multipart("/api/v1/messages/groups/{id}", id);
        if (file) request.file(new MockMultipartFile("files", "reading.txt", "text/plain", "Synthetic group material".getBytes(StandardCharsets.UTF_8)));
        request.param("body", text);
        if (replyTo != null) request.param("replyToId", replyTo);
        return mvc.perform(auth(request, token));
    }
    ResultActions direct(String token, List<Long> ids, String reply) throws Exception {
        var request = multipart("/api/v1/messages").param("recipientIds", ids.stream().map(String::valueOf).toArray(String[]::new)).param("subject", "Synthetic note").param("body", "Synthetic private text");
        if (reply != null) request.param("replyToId", reply);
        return mvc.perform(auth(request, token));
    }
    Set<Long> contacts(String token) throws Exception {
        JsonNode result = data(mvc.perform(auth(get("/api/v1/messages/directory"), token)).andExpect(status().isOk()).andReturn());
        Set<Long> ids = new HashSet<>(); result.forEach(item -> { ids.add(item.path("id").asLong()); assertThat(item.has("email")).isFalse(); }); return ids;
    }

    @Test void basicAccountsCanContactAdminsAndReplyButCannotContactUnrelatedUsers() throws Exception {
        mvc.perform(get("/api/v1/messages/directory")).andExpect(status().isUnauthorized());
        assertThat(contacts(memberToken)).contains(admin.getId()).doesNotContain(owner.getId(), outsider.getId(), member.getId());
        String messageId = data(direct(memberToken, List.of(admin.getId()), null).andExpect(status().isOk()).andReturn()).path("id").asText();
        direct(adminToken, List.of(member.getId()), messageId).andExpect(status().isOk());
        long before = messages.count();
        direct(memberToken, List.of(admin.getId(), outsider.getId()), null).andExpect(status().isForbidden());
        direct(memberToken, List.of(member.getId()), null).andExpect(status().isForbidden());
        direct(memberToken, List.of(99999999L), null).andExpect(status().isForbidden());
        assertThat(messages.count()).isEqualTo(before);
        mvc.perform(auth(get("/api/v1/messages/{id}", messageId), outsiderToken)).andExpect(status().isForbidden());
        admin.setStatus(UserStatus.DISABLED); users.save(admin);
        direct(memberToken, List.of(admin.getId()), null).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/messages/inbox"), adminToken)).andExpect(status().isUnauthorized());
    }

    @Test void membersCanDiscussShareReplyAndMessageEachOtherWithoutPlatformAdminPrivileges() throws Exception {
        long id = group(ownerToken);
        membership(id, ownerToken, member, "MEMBER", Set.of()).andExpect(status().isOk());
        assertThat(contacts(memberToken)).contains(owner.getId(), admin.getId()).doesNotContain(outsider.getId());
        direct(memberToken, List.of(owner.getId()), null).andExpect(status().isOk());
        JsonNode posted = data(postGroup(id, memberToken, "A question", null, true).andExpect(status().isOk()).andReturn());
        String messageId = posted.path("id").asText();
        postGroup(id, ownerToken, "A reply", messageId, false).andExpect(status().isOk()).andExpect(jsonPath("$.data.replyToId").value(messageId));
        mvc.perform(auth(get("/api/v1/messages/groups/{id}", id), memberToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(auth(get(posted.path("attachments").get(0).path("downloadUrl").asText()), ownerToken)).andExpect(status().isOk()).andExpect(content().string("Synthetic group material"));
        verify(scanner).scan(any(byte[].class));
        mvc.perform(auth(get("/api/v1/workspaces/{id}/directory", id), memberToken)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/users"), memberToken)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/messages/groups/{id}", id), outsiderToken)).andExpect(status().isForbidden());
        postGroup(id, outsiderToken, "Forbidden", null, false).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/messages/{id}", messageId), outsiderToken)).andExpect(status().isForbidden());
        postGroup(id, adminToken, "Admin support", null, false).andExpect(status().isOk());
    }

    @Test void removedMembersIncludingOriginalSendersLoseAllGroupMessageAndAttachmentRoutes() throws Exception {
        long id = group(ownerToken);
        membership(id, ownerToken, member, "MEMBER", Set.of()).andExpect(status().isOk());
        JsonNode posted = data(postGroup(id, memberToken, "Private material", null, true).andExpect(status().isOk()).andReturn());
        String messageId = posted.path("id").asText(), url = posted.path("attachments").get(0).path("downloadUrl").asText();
        var attachment = attachments.findById(posted.path("attachments").get(0).path("id").asLong()).orElseThrow();
        mvc.perform(auth(get("/api/v1/files/{id}/content", attachment.getFile().getId()), memberToken)).andExpect(status().isForbidden());
        mvc.perform(auth(delete("/api/v1/workspaces/{id}/members/{userId}", id, member.getId()), ownerToken)).andExpect(status().isOk());
        for (String path : List.of("/api/v1/messages/groups/" + id, "/api/v1/messages/" + messageId, url))
            mvc.perform(auth(get(path), memberToken)).andExpect(status().isForbidden());
        postGroup(id, memberToken, "No longer a member", null, false).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/messages/sent"), memberToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(contacts(memberToken)).doesNotContain(owner.getId());
        mvc.perform(auth(get(url), outsiderToken)).andExpect(status().isForbidden());
        mvc.perform(auth(get(url), ownerToken)).andExpect(status().isOk());
    }

    @Test void readOnlyMembersCannotPostOrElevateTheirRoleAndLeavingRevokesReadAccess() throws Exception {
        long id = group(ownerToken);
        membership(id, ownerToken, member, "VIEWER", Set.of()).andExpect(status().isOk());
        postGroup(id, ownerToken, "Read only material", null, false).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/messages/groups/{id}", id), memberToken)).andExpect(status().isOk());
        postGroup(id, memberToken, "Forbidden", null, true).andExpect(status().isForbidden());
        verifyNoInteractions(scanner);
        membership(id, memberToken, member, "ADMIN", Set.of()).andExpect(status().isForbidden());
        membership(id, ownerToken, member, "VIEWER", Set.of("CONTENT_READ", "CONTENT_WRITE")).andExpect(status().isForbidden());
        direct(memberToken, List.of(admin.getId()), null).andExpect(status().isOk());
        mvc.perform(auth(delete("/api/v1/workspaces/{id}/members/me", id), memberToken)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/messages/groups/{id}", id), memberToken)).andExpect(status().isForbidden());
        mvc.perform(auth(delete("/api/v1/workspaces/{id}/members/me", id), ownerToken)).andExpect(status().isConflict());
    }

    @Test void groupAdminCannotGrantAdminRolesChangeOwnersOrCrossIntoOtherGroups() throws Exception {
        long id = group(ownerToken), other = group(adminToken);
        membership(id, ownerToken, member, "ADMIN", Set.of()).andExpect(status().isOk());
        membership(id, memberToken, outsider, "MEMBER", Set.of()).andExpect(status().isOk());
        membership(id, memberToken, outsider, "ADMIN", Set.of()).andExpect(status().isForbidden());
        membership(id, memberToken, owner, "MEMBER", Set.of()).andExpect(status().isConflict());
        mvc.perform(auth(delete("/api/v1/workspaces/{id}/members/{userId}", id, owner.getId()), memberToken)).andExpect(status().isConflict());
        membership(other, memberToken, member, "ADMIN", Set.of()).andExpect(status().isForbidden());
        mvc.perform(json(patch("/api/v1/users/{id}", member.getId()), memberToken, Map.of("roleId", admin.getRole().getId()))).andExpect(status().isForbidden());
        membership(id, ownerToken, outsider, "ADMIN", Set.of()).andExpect(status().isOk());
        mvc.perform(auth(delete("/api/v1/workspaces/{id}/members/{userId}", id, outsider.getId()), memberToken)).andExpect(status().isForbidden());
        assertThat(users.findById(member.getId()).orElseThrow().getRole().getCode()).isNotEqualTo("ADMIN");
    }

    @Test void repliesCannotReferenceOtherGroupsAndMalwareDoesNotCreateMessages() throws Exception {
        long first = group(ownerToken), second = group(ownerToken);
        String parent = data(postGroup(first, ownerToken, "First group", null, false).andExpect(status().isOk()).andReturn()).path("id").asText();
        postGroup(second, ownerToken, "Forged reply", parent, false).andExpect(status().isBadRequest());
        long before = messages.count();
        when(scanner.scan(any(byte[].class))).thenThrow(new BusinessException(HttpStatus.BAD_REQUEST, "MALWARE_DETECTED", "Synthetic scanner rejection"));
        postGroup(first, ownerToken, "Bad attachment", null, true).andExpect(status().isBadRequest());
        assertThat(messages.count()).isEqualTo(before);
        mvc.perform(auth(get("/api/v1/messages/groups/{id}", first).param("page", "-1"), ownerToken)).andExpect(status().isBadRequest());
    }

    @TestConfiguration static class FastPasswords {
        @Bean @Primary PasswordEncoder groupTestEncoder() { return new BCryptPasswordEncoder(4); }
    }
}
