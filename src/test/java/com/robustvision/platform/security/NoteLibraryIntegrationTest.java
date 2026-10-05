package com.robustvision.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:note_libraries;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.bootstrap.enabled=false", "app.worker.enabled=false", "app.rate-limit.enabled=false"})
@AutoConfigureMockMvc
class NoteLibraryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder encoder;
    String token, otherToken;

    @BeforeEach void setup() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RoleEntity role = roles.save(new RoleEntity("N_" + suffix, "Writer", "Synthetic", Set.of("note:read", "note:write")));
        token = login("writer" + suffix, role); otherToken = login("other" + suffix, role);
    }
    String login(String name, RoleEntity role) throws Exception {
        users.save(new UserEntity(name, encoder.encode("SyntheticNotes123!"), name, name + "@example.test", role));
        return data(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("username", name, "password", "SyntheticNotes123!"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("token").asText();
    }
    JsonNode data(byte[] bytes) throws Exception { return mapper.readTree(bytes).path("data"); }
    JsonNode create(Map<String, String> body) throws Exception {
        return data(mvc.perform(post("/api/v1/notes").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    @Test void libraryAndFormatPersistAndOldRequestsKeepDefaults() throws Exception {
        JsonNode legacy = create(Map.of("title", "Existing workflow", "body", "# Notes"));
        assertThat(legacy.path("library").asText()).isEqualTo("综合学习");
        assertThat(legacy.path("contentFormat").asText()).isEqualTo("MARKDOWN");
        String id = legacy.path("id").asText();
        mvc.perform(patch("/api/v1/notes/{id}", id).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("library", "英语学习", "contentFormat", "HTML", "body", "<h2>Reading</h2><p>学习记录</p>"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/notes/{id}", id).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.library").value("英语学习")).andExpect(jsonPath("$.data.contentFormat").value("HTML"));
        mvc.perform(get("/api/v1/notes").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].excerpt").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<h2>"))));
        mvc.perform(patch("/api/v1/notes/{id}", id).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentFormat\":\"SCRIPT\"}")).andExpect(status().isBadRequest());
    }

    @Test void htmlExportsAreSanitizedAndRenderAcrossAllFormats() throws Exception {
        String id = create(Map.of("title", "Learning 学习", "library", "计算机学习", "contentFormat", "HTML", "body",
                "<h2>Reading</h2><p>Hello <strong>world</strong> 学习</p><pre><code>print(42)</code></pre><script>alert('bad')</script><img src='https://example.test/track'><a href='javascript:alert(1)'>link</a>"))
                .path("id").asText();
        for (String format : List.of("md", "html", "txt", "pdf", "docx")) {
            byte[] bytes = mvc.perform(get("/api/v1/notes/{id}/export", id).param("format", format).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(bytes).isNotEmpty();
            if (Set.of("md", "html", "txt").contains(format)) {
                String text = new String(bytes, StandardCharsets.UTF_8);
                assertThat(text).contains("Reading", "学习", "print(42)").doesNotContain("<script", "javascript:", "example.test/track", "alert('bad')");
                if (format.equals("md")) assertThat(text).contains("## Reading", "**world**", "```").doesNotContain("<h2>");
                if (format.equals("txt")) assertThat(text).doesNotContain("<h2>");
            }
            if (format.equals("pdf")) {
                java.nio.file.Files.write(java.nio.file.Path.of("target", "learning-note.pdf"), bytes);
                try (var document = org.apache.pdfbox.Loader.loadPDF(bytes)) {
                    assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document)).contains("Reading", "学习").doesNotContain("<h2>", "alert('bad')");
                }
            }
            if (format.equals("docx")) {
                try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
                    java.util.zip.ZipEntry entry; String document = "";
                    while ((entry = zip.getNextEntry()) != null) if (entry.getName().equals("word/document.xml")) document = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(document).contains("Reading", "学习").doesNotContain("&lt;h2&gt;", "alert('bad')");
                }
            }
        }
    }

    @Test void libraryDoesNotGrantAccessToOtherUsers() throws Exception {
        String id = create(Map.of("title", "Private", "body", "Private text", "library", "英语学习")).path("id").asText();
        mvc.perform(get("/api/v1/notes").header("Authorization", "Bearer " + otherToken)).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/v1/notes/{id}", id).header("Authorization", "Bearer " + otherToken)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/notes/{id}", id).header("Authorization", "Bearer " + otherToken).contentType(MediaType.APPLICATION_JSON).content("{\"library\":\"计算机学习\"}"))
                .andExpect(status().isNotFound());
        for (String format : List.of("md", "html", "txt", "pdf", "docx"))
            mvc.perform(get("/api/v1/notes/{id}/export", id).param("format", format).header("Authorization", "Bearer " + otherToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/notes/{id}/export", id).param("format", "html")).andExpect(status().isUnauthorized());
    }
}
