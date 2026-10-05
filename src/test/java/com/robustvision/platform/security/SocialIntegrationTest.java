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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:social_security;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "app.bootstrap.enabled=false", "app.worker.enabled=false", "app.rate-limit.enabled=false"})
@AutoConfigureMockMvc @Import(SocialIntegrationTest.FastPasswords.class)
class SocialIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired UserRepository users;
    @Autowired RoleRepository roles; @Autowired ChatMessageRepository chats; @Autowired PasswordEncoder encoder;
    UserEntity alice, bob, outsider, admin;
    String a, b, o, ad;
    @BeforeEach void setup() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0,8);
        var basic = roles.save(new RoleEntity("SOC_" + suffix, "Member", "Synthetic", Set.of("note:read", "note:write")));
        var administrator = roles.findByCode("ADMIN").orElseGet(() -> roles.save(new RoleEntity("ADMIN", "Admin", "Synthetic", Permissions.allCodes())));
        alice = user("alice" + suffix, basic); bob = user("bob" + suffix, basic); outsider = user("other" + suffix, basic); admin = user("admin" + suffix, administrator);
        a = login(alice); b = login(bob); o = login(outsider); ad = login(admin);
    }
    UserEntity user(String name, RoleEntity role) { return users.save(new UserEntity(name, encoder.encode("SyntheticSocial123!"), name, name + "@example.test", role)); }
    String login(UserEntity user) throws Exception { return data(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("username",user.getUsername(),"password","SyntheticSocial123!")))).andExpect(status().isOk()).andReturn()).path("token").asText(); }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) { return request.header("Authorization", "Bearer " + token); }
    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, Object body) throws Exception { return auth(request, token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)); }
    JsonNode data(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsByteArray()).path("data"); }
    long request() throws Exception {
        mvc.perform(json(post("/api/v1/social/contacts"), a, Map.of("userId",bob.getId()))).andExpect(status().isOk());
        return data(mvc.perform(auth(get("/api/v1/social/contacts"), b)).andExpect(status().isOk()).andReturn()).get(0).path("id").asLong();
    }
    ResultActions action(long id, String token, String action) throws Exception { return mvc.perform(json(patch("/api/v1/social/contacts/{id}",id),token,Map.of("action",action))); }
    ResultActions send(long id, String token, String key, String body) throws Exception { return mvc.perform(json(post("/api/v1/social/contacts/{id}/messages",id),token,Map.of("clientId",key,"body",body))); }

    @Test void discoveryIsAuthenticatedLimitedPrivateAndCanBeDisabled() throws Exception {
        mvc.perform(get("/api/v1/social/people").param("q","bob")).andExpect(status().isUnauthorized());
        JsonNode result = data(mvc.perform(auth(get("/api/v1/social/people").param("q",bob.getUsername()),a)).andExpect(status().isOk()).andReturn());
        assertThat(result).hasSize(1); assertThat(result.get(0).properties()).hasSize(4);
        assertThat(result.get(0).has("email")).isFalse();
        mvc.perform(auth(get("/api/v1/social/people").param("q","%_"),a)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(auth(get("/api/v1/social/people").param("q","b"),a)).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/v1/social/settings"),b,Map.of("discoverable",false))).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/social/people").param("q",bob.getUsername()),a)).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(json(post("/api/v1/social/contacts"),a,Map.of("userId",bob.getId()))).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/social/contacts"),a,Map.of("userId",alice.getId()))).andExpect(status().isBadRequest());
    }
    @Test void consentIsRequiredAndNeitherOutsidersNorAdminsCanReadChat() throws Exception {
        long id = request(); String key = UUID.randomUUID().toString();
        send(id,a,key,"hello").andExpect(status().isNotFound());
        action(id,a,"accept").andExpect(status().isNotFound());
        action(id,o,"accept").andExpect(status().isNotFound());
        // A reciprocal request is still pending; explicit acceptance is necessary.
        mvc.perform(json(post("/api/v1/social/contacts"),b,Map.of("userId",alice.getId()))).andExpect(status().isOk());
        send(id,b,key,"hello").andExpect(status().isNotFound());
        action(id,b,"accept").andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/social/contacts"), a)).andExpect(jsonPath("$.data[0].identityCode").value(bob.getIdentityCode()));
        mvc.perform(auth(get("/api/v1/messages/directory"), a)).andExpect(jsonPath("$.data[?(@.id == " + bob.getId() + ")].identityCode").value(org.hamcrest.Matchers.contains(bob.getIdentityCode())));
        JsonNode message = data(send(id,a,key,"<script>hello</script>").andExpect(status().isOk()).andReturn());
        send(id,a,key,"<script>hello</script>").andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(message.path("id").asLong()));
        send(id,a,key,"changed").andExpect(status().isConflict());
        mvc.perform(auth(get("/api/v1/social/contacts/{id}/messages",id),b)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        for (String token : List.of(o,ad)) {
            mvc.perform(auth(get("/api/v1/social/contacts/{id}/messages",id),token)).andExpect(status().isNotFound());
            send(id,token,UUID.randomUUID().toString(),"forged").andExpect(status().isNotFound());
        }
        JsonNode note = data(mvc.perform(json(post("/api/v1/notes"),a,Map.of("title","Private","body","secret"))).andExpect(status().isOk()).andReturn());
        mvc.perform(auth(get("/api/v1/notes/{id}",note.path("id").asText()),b)).andExpect(status().isNotFound());
    }
    @Test void identitySearchIsExactCaseInsensitiveAndRespectsVisibilityAndBlocks() throws Exception {
        String code = bob.getIdentityCode();
        // A username resembling a code must not introduce an ambiguous second match.
        user(code, alice.getRole());
        mvc.perform(get("/api/v1/social/people").param("q", code)).andExpect(status().isUnauthorized());
        mvc.perform(auth(get("/api/v1/social/people").param("q", "  " + code.toLowerCase(Locale.ROOT) + "  "), a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].identityCode").value(code))
                .andExpect(jsonPath("$.data[0].id").value(bob.getId()));
        mvc.perform(auth(get("/api/v1/social/people").param("q", alice.getIdentityCode()), a)).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(json(put("/api/v1/social/settings"), b, Map.of("discoverable", false))).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/social/people").param("q", code), a)).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(json(put("/api/v1/social/settings"), b, Map.of("discoverable", true))).andExpect(status().isOk());
        long id = request(); action(id, b, "block").andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/social/people").param("q", code), a)).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(auth(get("/api/v1/social/people").param("q", alice.getIdentityCode()), b)).andExpect(jsonPath("$.data").isEmpty());
        bob.setStatus(UserStatus.DISABLED); users.save(bob);
        mvc.perform(auth(get("/api/v1/social/people").param("q", code), o)).andExpect(jsonPath("$.data").isEmpty());
    }
    @Test void blockingStopsChatDiscoveryAndExistingDirectMailAndUnblockDoesNotRestoreFriendship() throws Exception {
        long id=request(); action(id,b,"accept").andExpect(status().isOk());
        mvc.perform(auth(multipart("/api/v1/messages").param("recipientIds",bob.getId().toString()).param("subject","Friend").param("body","Hello"),a)).andExpect(status().isOk());
        action(id,b,"block").andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/social/people").param("q",bob.getUsername()),a)).andExpect(jsonPath("$.data").isEmpty());
        send(id,a,UUID.randomUUID().toString(),"no").andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/social/contacts/{id}/messages",id),a)).andExpect(status().isNotFound());
        mvc.perform(auth(multipart("/api/v1/messages").param("recipientIds",bob.getId().toString()).param("subject","Friend").param("body","No"),a)).andExpect(status().isForbidden());
        action(id,b,"unblock").andExpect(status().isOk());
        send(id,a,UUID.randomUUID().toString(),"no").andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/social/contacts"),a,Map.of("userId",bob.getId()))).andExpect(status().isTooManyRequests());
    }
    @Test void cursorsAreBoundedAndDisabledUsersAreUnavailable() throws Exception {
        long id=request(); action(id,b,"accept").andExpect(status().isOk());
        long first=data(send(id,a,UUID.randomUUID().toString(),"first").andExpect(status().isOk()).andReturn()).path("id").asLong();
        send(id,b,UUID.randomUUID().toString(),"second").andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/social/contacts/{id}/messages",id).param("after",String.valueOf(first)),a)).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].body").value("second"));
        mvc.perform(auth(get("/api/v1/social/contacts/{id}/messages",id).param("before",String.valueOf(first)),a)).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(auth(get("/api/v1/social/contacts/{id}/messages",id).param("after","-1"),a)).andExpect(status().isBadRequest());
        send(id,a,"invalid","hello").andExpect(status().isBadRequest());
        bob.setStatus(UserStatus.DISABLED); users.save(bob);
        send(id,a,UUID.randomUUID().toString(),"no").andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/social/contacts/{id}/messages",id),b)).andExpect(status().isUnauthorized());
    }
    @Test void noteCreationRetryIsIdempotentAndStaleRevisionsCannotOverwrite() throws Exception {
        String key=UUID.randomUUID().toString(); Map<String,Object> payload=Map.of("title","Synced","body","original","clientId",key);
        JsonNode note=data(mvc.perform(json(post("/api/v1/notes"),a,payload)).andExpect(status().isOk()).andReturn());
        mvc.perform(json(post("/api/v1/notes"),a,payload)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(key));
        mvc.perform(json(post("/api/v1/notes"),b,payload)).andExpect(status().isConflict());
        mvc.perform(json(patch("/api/v1/notes/{id}",key),a,Map.of("body","new device","baseRevision",note.path("revision").asLong()))).andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        mvc.perform(json(patch("/api/v1/notes/{id}",key),a,Map.of("body","stale device","baseRevision",0))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NOTE_SYNC_CONFLICT"));
        mvc.perform(auth(get("/api/v1/notes/{id}",key),a)).andExpect(jsonPath("$.data.body").value("new device"));
    }
    @TestConfiguration static class FastPasswords { @Bean @Primary PasswordEncoder socialTestEncoder() { return new BCryptPasswordEncoder(4); } }
}
