package com.robustvision.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.domain.AiProvider;
import com.robustvision.platform.domain.FileScanStatus;
import com.robustvision.platform.service.AntivirusService;
import com.robustvision.platform.service.PersonalAiTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real loopback HTTP, real servlet/security/session/JPA stack, no MockMvc or forged authentication.
 * Only the outbound provider execute boundary and the antivirus verdict are synthetic test doubles.
 * Payload preparation, consent/ownership checks, file parsing and database persistence remain real.
 * With MYSQL_TEST_URL, creates and drops its own random schema; never uses the URL's existing schema.
 * Without MYSQL_TEST_URL, uses a fresh H2 schema and must not be reported as MySQL evidence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.bootstrap.enabled=true", "app.bootstrap.admin-username=http_test_admin",
        "app.bootstrap.admin-email=http_test_admin@example.invalid", "app.bootstrap.test-password=",
        "app.rate-limit.enabled=false", "app.personal-ai.remote-enabled=true",
        "app.storage.mode=filesystem", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF", "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
@Import(PersonalPlatformHttpIntegrationTest.FixedVocabularyClock.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PersonalPlatformHttpIntegrationTest {
    private static final String PASSWORD = "HttpTest9!" + UUID.randomUUID();
    private static final String KEY_A = "synthetic-http-key-a-" + UUID.randomUUID();
    private static final String KEY_B = "synthetic-http-key-b-" + UUID.randomUUID();
    private static final String MYSQL_INPUT = System.getenv("MYSQL_TEST_URL");
    private static final String MYSQL_USER = System.getenv().getOrDefault("MYSQL_TEST_USERNAME", "root");
    private static final String MYSQL_PASSWORD = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    private static String adminUrl, schema, databaseUrl;
    private static boolean createdSchema;
    private static Path storage;
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @SpyBean PersonalAiTransport transport;
    @MockBean AntivirusService antivirus;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private final List<PersonalAiTransport.Payload> sentPayloads = new CopyOnWriteArrayList<>();
    private int requests;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) throws Exception {
        storage = Files.createTempDirectory("rv-personal-http-");
        properties.add("app.storage.root", () -> storage.toString());
        properties.add("app.storage.legacy-root", () -> storage.toString());
        properties.add("app.bootstrap.admin-password", () -> PASSWORD);
        if (MYSQL_INPUT != null && !MYSQL_INPUT.isBlank()) {
            if (!MYSQL_INPUT.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("MYSQL_TEST_URL must be a MySQL server URL");
            int path = MYSQL_INPUT.indexOf('/', "jdbc:mysql://".length());
            if (path < 0) throw new IllegalArgumentException("MYSQL_TEST_URL must include a slash after the server");
            int query = MYSQL_INPUT.indexOf('?', path);
            String options = query < 0 ? "" : MYSQL_INPUT.substring(query);
            String server = MYSQL_INPUT.substring(0, path + 1);
            schema = "rv_personal_http_" + UUID.randomUUID().toString().replace("-", "");
            adminUrl = server + options;
            databaseUrl = server + schema + options;
            try (var connection = DriverManager.getConnection(adminUrl, MYSQL_USER, MYSQL_PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
                createdSchema = true;
            }
            properties.add("spring.datasource.url", () -> databaseUrl);
            properties.add("spring.datasource.username", () -> MYSQL_USER);
            properties.add("spring.datasource.password", () -> MYSQL_PASSWORD);
            properties.add("spring.flyway.enabled", () -> true);
            properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        } else {
            databaseUrl = "jdbc:h2:mem:personal_http_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
            properties.add("spring.datasource.url", () -> databaseUrl);
            properties.add("spring.datasource.username", () -> "sa");
            properties.add("spring.datasource.password", () -> "");
            properties.add("spring.flyway.enabled", () -> false);
            properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        }
    }

    @AfterAll
    static void removeOnlyOwnedFixtures() throws Exception {
        try {
            if (createdSchema) {
                try (var connection = DriverManager.getConnection(adminUrl, MYSQL_USER, MYSQL_PASSWORD);
                     var statement = connection.createStatement()) {
                    statement.execute("DROP DATABASE `" + schema + "`");
                    createdSchema = false;
                }
            }
        } finally {
            if (storage != null && Files.exists(storage)) {
                try (var files = Files.walk(storage)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                }
            }
        }
    }

    @Test
    void privateFeaturesWorkThroughActualHttpWithExplicitSyntheticBoundaries() throws Exception {
        when(antivirus.scan(any(byte[].class))).thenReturn(
                new AntivirusService.ScanResult(FileScanStatus.CLEAN, "SYNTHETIC TEST ONLY; no antivirus performed"));
        doAnswer(invocation -> {
            // Do not print supplied keys, including on an assertion failure.
            assertThat(KEY_A.equals(invocation.getArgument(2, String.class))).as("owner A key selected").isTrue();
            assertThat(invocation.getArgument(0, AiProvider.class)).isEqualTo(AiProvider.OPENAI);
            var payload = invocation.getArgument(1, PersonalAiTransport.Payload.class);
            sentPayloads.add(payload);
            boolean vision = payload.json().contains("data:image/png;base64,");
            return new PersonalAiTransport.Completion(vision
                    ? "SYNTHETIC recognition fixture: receipt total 12.34; verify against original image."
                    : "SYNTHETIC notebook draft: supplied source only.", 11L, 7L);
        }).when(transport).execute(any(), any(), anyString());

        call("GET", "/api/v1/account/profile", null, null, 401);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String userA = "http_a_" + suffix, userB = "http_b_" + suffix;
        JsonNode registeredA = register(userA);
        register(userB);
        String a = login(userA), b = login(userB);
        assertThat(data(call("GET", "/api/v1/account/profile", a, null, 200)).path("username").asText()).isEqualTo(userA);
        JsonNode sessionsA = data(call("GET", "/api/v1/account/sessions", a, null, 200));
        assertThat(sessionsA.size()).isEqualTo(1);
        assertThat(sessionsA.get(0).path("current").asBoolean()).isTrue();
        String sessionA = sessionsA.get(0).path("id").asText();
        call("DELETE", "/api/v1/account/sessions/" + sessionA, b, Map.of("currentPassword", PASSWORD), 404);

        saveSetting(a, KEY_A);
        assertThat(data(call("GET", "/api/v1/personal-ai/settings", b, null, 200)).isEmpty()).isTrue();
        saveSetting(b, KEY_B);
        JsonNode settingA = data(call("GET", "/api/v1/personal-ai/settings", a, null, 200)).get(0);
        assertThat(settingA.path("configured").asBoolean()).isTrue();
        assertThat(settingA.has("apiKey")).isFalse();
        assertThat(settingA.has("encryptedKey")).isFalse();
        String cipher = jdbc.queryForObject("SELECT encrypted_key FROM personal_ai_setting WHERE owner_id=?", String.class,
                registeredA.path("id").asLong());
        assertThat(cipher != null && !cipher.contains(KEY_A)).as("key stored encrypted").isTrue();

        JsonNode textPreview = data(call("POST", "/api/v1/personal-ai/preview", a, previewBody(List.of()), 200));
        assertThat(textPreview.path("endpoint").asText()).isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(textPreview.path("outboundBytes").asInt()).isPositive();
        assertThat(sentPayloads).isEmpty();
        String textToken = textPreview.path("previewToken").asText();
        call("POST", "/api/v1/personal-ai/execute", a, executeBody(textToken, false), 400);
        call("POST", "/api/v1/personal-ai/execute", b, executeBody(textToken, true), 400);
        JsonNode textResult = data(call("POST", "/api/v1/personal-ai/execute", a, executeBody(textToken, true), 200));
        assertThat(textResult.path("result").asText()).startsWith("SYNTHETIC notebook draft");
        call("POST", "/api/v1/personal-ai/execute", a, executeBody(textToken, true), 400);
        assertThat(sentPayloads).hasSize(1);

        JsonNode file = upload(a);
        assertThat(file.path("contentType").asText()).isEqualTo("image/png");
        String fileId = file.path("id").asText();
        Map<String, Object> imageRequest = Map.of("provider", "OPENAI", "fileId", fileId, "taskType", "RECEIPT");
        call("POST", "/api/v1/personal-ai/recognition/preview", b, imageRequest, 404);
        JsonNode imagePreview = data(call("POST", "/api/v1/personal-ai/recognition/preview", a, imageRequest, 200));
        assertThat(imagePreview.path("mime").asText()).isEqualTo("image/png");
        assertThat(imagePreview.path("sizeBytes").asInt()).isEqualTo(PNG.length);
        assertThat(imagePreview.path("outboundBytes").asInt()).isGreaterThan(PNG.length);
        assertThat(sentPayloads).hasSize(1);
        String imageToken = imagePreview.path("previewToken").asText();
        call("POST", "/api/v1/personal-ai/recognition/execute", a, executeBody(imageToken, false), 400);
        call("POST", "/api/v1/personal-ai/recognition/execute", b, executeBody(imageToken, true), 400);
        JsonNode recognition = data(call("POST", "/api/v1/personal-ai/recognition/execute", a, executeBody(imageToken, true), 200));
        String resultId = recognition.path("id").asText();
        assertThat(recognition.path("result").asText()).startsWith("SYNTHETIC recognition fixture");
        call("POST", "/api/v1/personal-ai/recognition/execute", a, executeBody(imageToken, true), 400);
        assertThat(data(call("GET", "/api/v1/personal-ai/recognition/results", a, null, 200)).size()).isEqualTo(1);
        assertThat(data(call("GET", "/api/v1/personal-ai/recognition/results", b, null, 200)).isEmpty()).isTrue();
        assertThat(sentPayloads).hasSize(2);
        assertThat(sentPayloads.get(1).json()).contains("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG));
        verify(antivirus).scan(any(byte[].class));

        String sourceId = "r:" + resultId;
        JsonNode sourcesA = data(call("GET", "/api/v1/notes/sources/experiments", a, null, 200));
        assertThat(sourcesA.get(0).path("taskId").asText()).isEqualTo(sourceId);
        assertThat(data(call("GET", "/api/v1/notes/sources/experiments", b, null, 200)).isEmpty()).isTrue();
        call("POST", "/api/v1/notes/sources/experiments/preview", b, Map.of("taskIds", List.of(sourceId)), 404);
        JsonNode source = data(call("POST", "/api/v1/notes/sources/experiments/preview", a, Map.of("taskIds", List.of(sourceId)), 200));
        String markdown = source.path("markdown").asText();
        assertThat(markdown).contains(resultId, "SYNTHETIC recognition fixture");
        call("POST", "/api/v1/personal-ai/preview", b, previewBody(List.of(sourceId)), 404);
        JsonNode sourcePreview = data(call("POST", "/api/v1/personal-ai/preview", a, previewBody(List.of(sourceId)), 200));
        assertThat(sourcePreview.path("context").asText()).contains(resultId, "SYNTHETIC recognition fixture");
        call("POST", "/api/v1/personal-ai/execute", a, executeBody(sourcePreview.path("previewToken").asText(), true), 200);
        assertThat(sentPayloads.get(2).json()).contains(resultId, "SYNTHETIC recognition fixture");

        JsonNode note = data(call("POST", "/api/v1/notes", a,
                Map.of("title", "Own HTTP notebook", "body", markdown, "tags", "synthetic,http"), 200));
        String noteId = note.path("id").asText();
        assertThat(data(call("GET", "/api/v1/notes/" + noteId, a, null, 200)).path("body").asText()).isEqualTo(markdown);
        call("GET", "/api/v1/notes/" + noteId, b, null, 404);
        call("PATCH", "/api/v1/notes/" + noteId, b, Map.of("body", "owner attack"), 404);
        call("DELETE", "/api/v1/notes/" + noteId, b, null, 404);
        assertThat(data(call("GET", "/api/v1/notes", b, null, 200)).isEmpty()).isTrue();
        JsonNode changed = data(call("PATCH", "/api/v1/notes/" + noteId, a,
                Map.of("title", "Updated HTTP notebook", "body", markdown + "\nChecked by synthetic test."), 200));
        assertThat(changed.path("title").asText()).isEqualTo("Updated HTTP notebook");
        assertThat(data(call("GET", "/api/v1/notes", a, null, 200)).size()).isEqualTo(1);

        String longOriginal = "x".repeat(24000) + "IMPORTANT_ORIGINAL_TAIL";
        call("PATCH", "/api/v1/notes/" + noteId, a, Map.of("body", longOriginal), 200);
        JsonNode tooLong = call("POST", "/api/v1/notes/" + noteId + "/assist", a,
                Map.of("action", "tidy", "body", "server uses saved note"), 413);
        assertThat(tooLong.path("error").path("code").asText()).isEqualTo("ASSIST_BODY_TOO_LARGE");
        assertThat(data(call("GET", "/api/v1/notes/" + noteId, a, null, 200)).path("body").asText()).isEqualTo(longOriginal);

        JsonNode usage = data(call("GET", "/api/v1/account/usage", a, null, 200));
        assertThat(usage.path("ai").path("total").asInt()).isEqualTo(3);
        assertThat(usage.path("ai").path("knownInputTokens").asInt()).isEqualTo(33);
        assertThat(usage.path("ai").path("knownOutputTokens").asInt()).isEqualTo(21);
        assertThat(usage.path("recognitionCount").asInt()).isEqualTo(1);
        assertThat(usage.path("files").path("count").asInt()).isEqualTo(1);
        assertThat(usage.path("noteCount").asInt()).isEqualTo(1);
        assertThat(data(call("GET", "/api/v1/personal-ai/usage", a, null, 200)).size()).isEqualTo(3);
        JsonNode usageB = data(call("GET", "/api/v1/account/usage", b, null, 200));
        assertThat(usageB.path("ai").path("total").asInt()).isZero();
        assertThat(usageB.path("recognitionCount").asInt()).isZero();
        assertThat(usageB.path("noteCount").asInt()).isZero();

        vocabulary(a, b);
        call("DELETE", "/api/v1/notes/" + noteId, a, null, 200);
        call("GET", "/api/v1/notes/" + noteId, a, null, 404);
        call("DELETE", "/api/v1/personal-ai/settings/OPENAI", b, null, 200);
        assertThat(data(call("GET", "/api/v1/personal-ai/settings", a, null, 200)).size()).isEqualTo(1);
        call("POST", "/api/v1/account/logout", a, null, 200);
        call("GET", "/api/v1/account/profile", a, null, 401);
        call("POST", "/api/v1/account/logout", a, null, 401);
        call("GET", "/api/v1/account/profile", b, null, 200);
        assertThat(sentPayloads).as("exactly three approved provider-boundary executions").hasSize(3);
        String database = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName() + " " + connection.getMetaData().getDatabaseProductVersion());
        if (MYSQL_INPUT != null && !MYSQL_INPUT.isBlank()) assertThat(database).startsWith("MySQL ");
        System.out.printf("PRIVATE_HTTP_EVIDENCE requests=%d database=%s real_http=true upstream=SYNTHETIC_EXECUTE_BOUNDARY antivirus=SYNTHETIC_CLEAN_NOT_REAL_SCAN provider_calls=3 credentials_redacted=true%n", requests, database);
    }

    private void vocabulary(String a, String b) throws Exception {
        String[] terms = {"cobalt", "meadow", "lantern", "orbit"};
        String[] meanings = {"钴蓝色", "草地", "灯笼", "轨道"};
        var words = new ArrayList<Map<String, Object>>();
        var answers = new HashMap<String, String>();
        for (int i = 0; i < terms.length; i++) {
            var distractors = new ArrayList<>(List.of(meanings));
            distractors.remove(meanings[i]);
            words.add(Map.of("term", terms[i], "ipa", "/test/", "pos", "n.", "meaning", meanings[i],
                    "example", "This original test sentence uses " + terms[i] + ".",
                    "exampleTranslation", "原创测试例句", "distractors", distractors));
            answers.put(terms[i], meanings[i]);
        }
        JsonNode book = data(call("POST", "/api/v1/vocabulary/books/import", a, Map.of("title", "Original HTTP test vocabulary",
                "description", "Four original synthetic test entries", "attribution", "Original test fixtures",
                "rightsConfirmed", true, "words", words), 200));
        String bookId = book.path("id").asText();
        JsonNode missingZone = call("POST", "/api/v1/vocabulary/next", a, Map.of("bookId", bookId, "mode", "LEARN"), 400);
        assertThat(missingZone.path("error").path("code").asText()).isEqualTo("VOCAB_TIMEZONE_REQUIRED");
        call("PUT", "/api/v1/vocabulary/settings", a, Map.of("zoneId", "Etc/UTC", "dailyGoal", 1, "selectedBookId", bookId), 200);
        call("GET", "/api/v1/vocabulary/books/" + bookId + "/words", b, null, 404);
        String learnedTerm = null;
        for (int i = 1; i <= 4; i++) {
            JsonNode q = data(call("POST", "/api/v1/vocabulary/next", a, Map.of("bookId", bookId, "mode", "LEARN"), 200)).path("question");
            assertThat(q.has("correctOptionId")).isFalse();
            assertThat(q.has("meaning")).isFalse();
            String term = q.path("term").asText();
            if (learnedTerm == null) learnedTerm = term;
            assertThat(term).isEqualTo(learnedTerm);
            String option = null;
            for (JsonNode value : q.path("options")) if (answers.get(term).equals(value.path("meaning").asText())) option = value.path("id").asText();
            assertThat(option).isNotBlank();
            String path = "/api/v1/vocabulary/questions/" + q.path("id").asText() + "/answer";
            if (i == 1) call("POST", path, b, Map.of("optionId", option), 404);
            JsonNode result = data(call("POST", path, a, Map.of("optionId", option), 200));
            assertThat(result.path("correct").asBoolean()).isTrue();
            assertThat(result.path("learningCorrect").asInt()).isEqualTo(i);
            if (i == 4) {
                assertThat(result.path("newlyLearned").asBoolean()).isTrue();
                assertThat(result.path("dueDate").asText()).isEqualTo("2026-10-03");
            }
        }
        JsonNode dashboard = data(call("GET", "/api/v1/vocabulary/dashboard", a, null, 200));
        assertThat(dashboard.path("today").path("learned").asInt()).isEqualTo(1);
        assertThat(dashboard.path("today").path("correct").asInt()).isEqualTo(4);
        assertThat(dashboard.path("settings").path("zoneId").asText()).isEqualTo("Etc/UTC");
        assertThat(data(call("POST", "/api/v1/vocabulary/next", a, Map.of("bookId", bookId, "mode", "LEARN"), 200)).path("question").isNull()).isTrue();
        assertThat(data(call("GET", "/api/v1/vocabulary/dashboard", b, null, 200)).path("today").path("learned").asInt()).isZero();
    }

    private JsonNode register(String username) throws Exception {
        return data(call("POST", "/api/v1/auth/register", null,
                Map.of("username", username, "email", username + "@example.invalid", "password", PASSWORD), 200));
    }
    private String login(String username) throws Exception {
        JsonNode response = call("POST", "/api/v1/auth/login", null, Map.of("username", username, "password", PASSWORD), 200);
        String token = data(response).path("token").asText();
        assertThat(!token.isBlank()).as("login returned token").isTrue();
        tokens.add(token);
        return token;
    }
    private void saveSetting(String token, String key) throws Exception {
        call("PUT", "/api/v1/personal-ai/settings/OPENAI", token,
                Map.of("model", "synthetic-http-model", "apiKey", key, "enabled", true), 200);
    }
    private static Map<String, Object> previewBody(List<String> sources) {
        return Map.of("provider", "OPENAI", "action", "draft", "title", "Synthetic test", "body", "Owner A notebook text", "selectedTaskIds", sources);
    }
    private static Map<String, Object> executeBody(String token, boolean confirmed) {
        return Map.of("previewToken", token, "confirmed", confirmed);
    }
    private static JsonNode data(JsonNode response) { return response.path("data"); }
    private JsonNode call(String method, String path, String token, Object body, int expected) throws Exception {
        byte[] bytes = body == null ? new byte[0] : mapper.writeValueAsBytes(body);
        return send(method, path, token, bytes, "application/json", expected);
    }
    private JsonNode upload(String token) throws Exception {
        String boundary = "PrivateHttpBoundary" + UUID.randomUUID();
        var bytes = new ByteArrayOutputStream();
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(PNG);
        bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return data(send("POST", "/api/v1/files", token, bytes.toByteArray(), "multipart/form-data; boundary=" + boundary, 200));
    }
    private JsonNode send(String method, String path, String token, byte[] body, String contentType, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType).header("User-Agent", "SyntheticPrivatePlatformHttpTest")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requests++;
        String text = response.body();
        assertThat(!text.contains(PASSWORD) && !text.contains(KEY_A) && !text.contains(KEY_B)).as("secret absent from HTTP response").isTrue();
        if (!path.equals("/api/v1/auth/login")) for (String issued : tokens)
            assertThat(!text.contains(issued)).as("session token absent from non-login response").isTrue();
        JsonNode json = mapper.readTree(text);
        assertThat(response.statusCode()).as("%s %s (error code %s)", method, path, json.path("error").path("code").asText()).isEqualTo(expected);
        return json;
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedVocabularyClock {
        @Bean Clock vocabularyClock() { return Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC); }
    }
}
