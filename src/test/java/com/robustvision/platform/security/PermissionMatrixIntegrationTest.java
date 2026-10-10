package com.robustvision.platform.security;

import com.fasterxml.jackson.databind.*;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real JWT requests: a module grant must never imply unrelated or administrative access. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:admin_suite;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;TIME ZONE=UTC", "app.bootstrap.enabled=false", "app.worker.enabled=false", "app.rate-limit.enabled=false"})
@AutoConfigureMockMvc @Import(AdministrationIntegrationTest.FastPasswords.class)
class PermissionMatrixIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder passwords;
    @org.springframework.boot.test.mock.mockito.MockBean com.robustvision.platform.service.LiveUpdateService live;
    @org.springframework.boot.test.mock.mockito.MockBean com.robustvision.platform.service.PersonalTrainingService training;
    UserEntity account;
    static final String PASSWORD = "SyntheticMatrix123!";
    record Endpoint(String path, List<Set<String>> clauses) {
        boolean allowed(Set<String> permissions) { return clauses.stream().allMatch(c -> !Collections.disjoint(c, permissions)); }
    }
    static Endpoint endpoint(String path, String... clauses) {
        return new Endpoint("/api/v1"+path, Arrays.stream(clauses).map(c -> Set.of(c.split(","))).toList());
    }
    static final List<Endpoint> ENDPOINTS = List.of(
        endpoint("/dashboard/summary","dashboard:read"),
        endpoint("/knowledge/topics","knowledge:read"), endpoint("/knowledge/domains","knowledge:read"), endpoint("/knowledge/entries","knowledge:read"),
        endpoint("/notes","note:read"), endpoint("/notes/trash","note:read"), endpoint("/notes/reminders","note:read"),
        endpoint("/files","file:read,file:read:any"), endpoint("/inference/tasks","experiment:read,experiment:read:any"),
        endpoint("/models","model:read"), endpoint("/models/runtime","model:read"),
        endpoint("/billing/summary","billing:read"), endpoint("/billing/providers","billing:read:any"),
        endpoint("/billing/admin/wallets","billing:manage","ADMIN_ONLY"), endpoint("/billing/credentials","credential:manage","ADMIN_ONLY"),
        endpoint("/messages/directory","message:read"), endpoint("/messages/inbox","message:read"), endpoint("/messages/sent","message:read"),
        endpoint("/workspaces","group:use"), endpoint("/workspaces/overview","group:use"), endpoint("/workspaces/invitations","group:use"),
        endpoint("/social/settings","contacts:use"), endpoint("/social/people?q=synthetic-matrix","contacts:use"), endpoint("/social/contacts","contacts:use"),
        endpoint("/vocabulary/dashboard","vocabulary:use"), endpoint("/vocabulary/statistics","vocabulary:use"),
        endpoint("/research/search?q=&type=ALL","research:use"), endpoint("/research/records?kind=CARD","research:use"),
        endpoint("/research/evaluations","research:use","experiment:read,experiment:read:any"),
        endpoint("/research/bookmarks","research:use"), endpoint("/research/saved-searches","research:use"),
        endpoint("/personal-ai/settings","personal-ai:manage,personal-ai:use"), endpoint("/personal-ai/providers","personal-ai:manage,personal-ai:use"),
        endpoint("/personal-ai/memories","personal-ai:manage"), endpoint("/personal-ai/training/environment","training:use"),
        endpoint("/personal-ai/training/jobs","training:use"), endpoint("/personal-ai/recognition/results","personal-ai:use","experiment:run"),
        endpoint("/moderation/reports","moderation:review"), endpoint("/admin/statistics","admin:stats"), endpoint("/admin/audit","admin:audit"),
        endpoint("/admin/users","user:read"), endpoint("/users","user:read"), endpoint("/roles","role:read"), endpoint("/permissions","role:read")
    );
    @BeforeEach void setup() {
        String suffix = UUID.randomUUID().toString().substring(0,8);
        RoleEntity role = roles.save(new RoleEntity("MATRIX_"+suffix,"Matrix","Synthetic",Permissions.allCodes()));
        account = users.save(new UserEntity("matrix"+suffix,passwords.encode(PASSWORD),"Matrix",suffix+"@example.test",role));
        org.mockito.Mockito.when(training.environment()).thenReturn(json.createObjectNode().put("ready",true));
        org.mockito.Mockito.when(training.list()).thenReturn(json.createArrayNode());
    }
    String login() throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("username",account.getUsername(),"password",PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("token").asText();
    }
    void matrix(String token, Set<String> permissions) throws Exception {
        var view = mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn();
        assertThat(json.convertValue(json.readTree(view.getResponse().getContentAsByteArray()).path("data").path("permissions"),Set.class)).containsExactlyInAnyOrderElementsOf(permissions);
        for(Endpoint endpoint:ENDPOINTS) {
            Set<String> required=new LinkedHashSet<>(permissions);
            if("ADMIN".equals(account.getRole().getCode()))required.add("ADMIN_ONLY");
            var response = mvc.perform(get(endpoint.path()).header("Authorization","Bearer "+token)).andReturn().getResponse();
            assertThat(response.getStatus()).as("%s with %s: %s",endpoint.path(),permissions,response.getContentAsString())
                    .isEqualTo(endpoint.allowed(required)?200:403);
        }
    }
    @Test void eachIndividualDenyOverridesAnOtherwiseFullyEnabledRole() throws Exception {
        String token = login();
        for(String permission:Permissions.allCodes()) {
            account.setPermissionOverrides(Map.of(permission,false)); users.save(account);
            Set<String> expected=new LinkedHashSet<>(Permissions.allCodes());expected.remove(permission);
            matrix(token,expected);
        }
    }
    @Test void eachIndividualGrantDoesNotUnlockUnrelatedModules() throws Exception {
        account.getRole().setPermissions(Set.of());roles.save(account.getRole());
        String token = login();
        for(String permission:Permissions.allCodes()) {
            account.setPermissionOverrides(Map.of(permission,true));users.save(account);
            matrix(token,Set.of(permission));
        }
    }
    @Test void viewerResearcherAndAdministratorMatchTheirResolvedPermissions() throws Exception {
        for(String role:List.of("VIEWER","RESEARCHER","ADMIN")) {
            Set<String> expected="VIEWER".equals(role)?Permissions.viewer():"RESEARCHER".equals(role)?Permissions.researcher():Permissions.allCodes();
            RoleEntity stored="ADMIN".equals(role)?roles.findByCode("ADMIN").orElseGet(()->roles.save(new RoleEntity("ADMIN","Admin","Synthetic",Set.of())))
                    :roles.save(new RoleEntity(role+"_"+UUID.randomUUID().toString().substring(0,8),role,"Synthetic",expected));
            account.setRole(stored);users.save(account);matrix(login(),expected);
        }
    }
    @Test void missingQueryAndMultipartParametersReturnSafeClientErrors() throws Exception {
        String token=login();
        mvc.perform(get("/api/v1/social/people").header("Authorization","Bearer "+token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(multipart("/api/v1/files").header("Authorization","Bearer "+token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/admin/users?page=private-synthetic-value").header("Authorization","Bearer "+token))
                .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-synthetic-value"))));
    }
}
