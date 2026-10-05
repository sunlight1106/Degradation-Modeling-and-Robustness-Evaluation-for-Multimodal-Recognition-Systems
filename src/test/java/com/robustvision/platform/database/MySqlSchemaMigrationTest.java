package com.robustvision.platform.database;

import jakarta.persistence.Entity;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Opt-in integration contract against a real disposable MySQL 8.4 server.
 * Each test creates and drops ONLY its own random rv_schema_test_* database.
 * Existing URL-selected databases are never migrated, cleaned or dropped.
 * See database/README.md for credentials and the execution command.
 */
@Tag("mysql")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = "jdbc:mysql:.*")
class MySqlSchemaMigrationTest {
    private static final Set<String> LEGACY_DOMAIN_TABLES = Set.of(
            "app_role", "role_permission", "app_user", "file_asset", "model_definition", "inference_task",
            "user_wallet", "wallet_ledger", "recharge_order", "provider_budget", "workspace", "workspace_member",
            "workspace_member_permission", "internal_message", "message_recipient", "message_attachment",
            "provider_credential", "knowledge_topic", "knowledge_entry", "note", "note_reference", "note_share");

    private static final Set<String> DOMAIN_TABLES = java.util.stream.Stream.concat(
            LEGACY_DOMAIN_TABLES.stream(), java.util.stream.Stream.of("personal_ai_setting", "personal_ai_usage", "user_session",
                    "vocabulary_book", "vocabulary_word", "vocabulary_profile", "vocabulary_progress", "vocabulary_question", "personal_recognition_result"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    @Test
    void freshMigrationsCoverAllEntitiesAndConstraintsOnMySql84() throws Exception {
        try (TestDatabase database = new TestDatabase()) {
            Flyway flyway = database.flyway(null);
            assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(14);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (Connection connection = database.connect()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
                assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("8.4.");
                assertDomainTables(connection);
                seedAllDomains(connection);
                seedAccountSettings(connection);
                assertFixtureAndConstraints(connection);
                assertIndexes(connection);
            }
            database.validateHibernateMappings();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"6", "7"})
    void populatedBaselineUpgradePreservesEveryDomainRowAndAppliedChecksums(String baseline) throws Exception {
        try (TestDatabase database = new TestDatabase()) {
            Flyway v6 = database.flyway(baseline);
            assertThat(v6.migrate().migrationsExecuted).isEqualTo(Integer.parseInt(baseline));
            Map<String, List<String>> before;
            List<String> checksums;
            try (Connection connection = database.connect()) {
                seedAllDomains(connection);
                before = snapshot(connection);
                checksums = rows(connection,
                        "SELECT version, checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
            }
            Flyway latest = database.flyway(null);
            assertThat(latest.migrate().migrationsExecuted).isGreaterThanOrEqualTo(1);
            latest.validate();
            assertThat(latest.migrate().migrationsExecuted).isZero();
            try (Connection connection = database.connect()) {
                assertThat(snapshot(connection)).isEqualTo(before);
                seedAccountSettings(connection);
                assertThat(rows(connection,
                        "SELECT version, checksum FROM flyway_schema_history WHERE CAST(version AS UNSIGNED) <= " + baseline + " ORDER BY installed_rank"))
                        .isEqualTo(checksums);
                assertFixtureAndConstraints(connection);
                assertIndexes(connection);
            }
            database.validateHibernateMappings();
        }
    }

    private static void assertDomainTables(Connection connection) throws SQLException {
        Set<String> tables = new TreeSet<>();
        try (ResultSet rs = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
        }
        tables.remove("flyway_schema_history");
        assertThat(tables).containsExactlyInAnyOrderElementsOf(DOMAIN_TABLES);
        assertThat(scalar(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND engine <> 'InnoDB'"))
                .isEqualTo("0");
        assertThat(scalar(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_collation NOT LIKE 'utf8mb4%'"))
                .isEqualTo("0");
    }

    private static void assertFixtureAndConstraints(Connection c) throws SQLException {
        assertThat(scalar(c, "SELECT COUNT(*) FROM internal_message WHERE workspace_id IS NOT NULL OR reply_to_id IS NOT NULL")).isEqualTo("0");
        assertThatThrownBy(() -> { try (Statement statement = c.createStatement()) {
            statement.executeUpdate("UPDATE internal_message SET workspace_id = 99999999 WHERE id = 'message-1'");
        } }).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> { try (Statement statement = c.createStatement()) {
            statement.executeUpdate("UPDATE internal_message SET reply_to_id = 'missing-message' WHERE id = 'message-1'");
        } }).isInstanceOf(SQLException.class);
        assertThat(scalar(c, "SELECT title FROM note WHERE id = 'note-1'"))
                .isEqualTo("多模态评测 🧪");
        assertThat(scalar(c, "SELECT baseline_result FROM inference_task WHERE id = 'task-1'"))
                .isEqualTo("{\"prediction\":\"中文 🚗\"}");
        assertThat(scalar(c, "SELECT balance_cny FROM user_wallet WHERE user_id = 101")).isEqualTo("12.3456");
        assertThat(scalar(c, "SELECT cost_cny FROM inference_task WHERE id = 'task-1'")).isEqualTo("0.123456");
        assertThat(scalar(c, "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE()"))
                .isEqualTo("42");
        assertDuplicateRejected(c, "INSERT INTO workspace_member (workspace_id, user_id, member_role) VALUES (201, 101, 'MEMBER')");
        assertDuplicateRejected(c, "INSERT INTO note_reference (note_id, reference_type, reference_id) VALUES ('note-1', 'FILE', 'file-1')");
        assertDuplicateRejected(c, "INSERT INTO note_share (id, note_id, shared_by, token) VALUES ('duplicate-share', 'note-1', 101, '0123456789abcdef0123456789abcdef')");
        assertThatThrownBy(() -> execute(c,
                "INSERT INTO note (id, owner_id, title, body) VALUES ('orphan-note', 999999, 'orphan', '')"))
                .isInstanceOf(SQLException.class).satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(1452));

        assertThat(scalar(c, "SELECT library FROM note WHERE id = 'note-1'")).isEqualTo("综合学习");
        assertThat(scalar(c, "SELECT content_format FROM note WHERE id = 'note-1'")).isEqualTo("MARKDOWN");

        // Delete only the disposable child note, then verify all dependent rows cascade.
        execute(c, "INSERT INTO note (id, owner_id, title, body) VALUES ('cascade-note', 101, 'temporary', '')");
        execute(c, "INSERT INTO note_reference (note_id, reference_type, reference_id) VALUES ('cascade-note', 'FILE', 'file-1')");
        execute(c, "INSERT INTO note_share (id, note_id, shared_by, token) VALUES ('cascade-share', 'cascade-note', 101, 'abcdef0123456789abcdef0123456789')");
        execute(c, "DELETE FROM note WHERE id = 'cascade-note'");
        assertThat(scalar(c, "SELECT COUNT(*) FROM note_reference WHERE note_id = 'cascade-note'")).isEqualTo("0");
        assertThat(scalar(c, "SELECT COUNT(*) FROM note_share WHERE note_id = 'cascade-note'")).isEqualTo("0");
    }

    private static void assertDuplicateRejected(Connection c, String sql) {
        assertThatThrownBy(() -> execute(c, sql)).isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(1062));
    }

    private static void assertIndexes(Connection c) throws SQLException {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("idx_message_workspace_created", "workspace_id,created_at,id"),
                Map.entry("idx_vocab_book_owner", "owner_id,created_at"),
                Map.entry("idx_vocab_progress_due", "owner_id,due_date"),
                Map.entry("idx_vocab_question_owner_time", "owner_id,created_at"),
                Map.entry("idx_vocab_question_owner_date", "owner_id,study_date"),
                Map.entry("idx_personal_recognition_owner_created", "owner_id,created_at"),
                Map.entry("idx_user_session_owner_expiry", "user_id,expires_at"),
                Map.entry("idx_user_session_expiry", "expires_at"),
                Map.entry("idx_personal_ai_usage_owner_time", "owner_id,created_at"),
                Map.entry("idx_task_created", "created_at,id"),
                Map.entry("idx_task_user_created", "requested_by,created_at"),
                Map.entry("idx_file_created", "created_at,id"),
                Map.entry("idx_user_role_status", "role_id,status"),
                Map.entry("idx_model_status_name", "status,name,id"),
                Map.entry("idx_member_user_created", "user_id,created_at,id"),
                Map.entry("idx_member_workspace_created", "workspace_id,created_at,id"),
                Map.entry("idx_topic_owner_sort", "owner_id,sort_order,id"),
                Map.entry("idx_entry_topic_sort_created", "topic_id,sort_order,created_at,id"),
                Map.entry("idx_note_owner_status_updated", "owner_id,status,updated_at,id"),
                Map.entry("idx_note_reference_note_sort", "note_id,sort_order,id"),
                Map.entry("idx_credential_active_created", "active,created_at,id"),
                Map.entry("idx_credential_provider_active_created", "provider,active,created_at,id"));
        Map<String, String> actual = new LinkedHashMap<>();
        try (Statement statement = c.createStatement(); ResultSet rs = statement.executeQuery("""
                SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                FROM information_schema.statistics WHERE table_schema = DATABASE()
                GROUP BY table_name, index_name
                """)) {
            while (rs.next()) actual.put(rs.getString(1), rs.getString(2));
        }
        assertThat(actual).containsAllEntriesOf(expected);
    }

    private static Map<String, List<String>> snapshot(Connection c) throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : new TreeSet<>(LEGACY_DOMAIN_TABLES)) {
            // Compare original columns across V13 message and V14 note additions.
            String columns = table.equals("internal_message") ? "id,sender_id,subject,body,created_at"
                    : table.equals("note") ? "id,owner_id,title,body,tags,status,created_at,updated_at" : "*";
            List<String> values = rows(c, "SELECT " + columns + " FROM `" + table + "`");
            values.sort(String::compareTo);
            result.put(table, values);
        }
        return result;
    }

    private static List<String> rows(Connection c, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = c.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    String value = rs.getString(i);
                    columns.add(value == null ? "NULL" : value.length() + ":" + value);
                }
                values.add(String.join("|", columns));
            }
        }
        return values;
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement statement = c.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement statement = c.createStatement()) { statement.execute(sql); }
    }

    static void seedAccountSettings(Connection c) throws SQLException {
        execute(c, "INSERT INTO personal_ai_setting (id, owner_id, provider, model, base_url, encrypted_key, enabled, updated_at) VALUES ('setting-fixture', 101, 'OPENAI', 'synthetic', 'https://example.invalid', 'synthetic-ciphertext', false, CURRENT_TIMESTAMP(6))");
        execute(c, "INSERT INTO personal_ai_usage (id, owner_id, provider, model, action, status, created_at) VALUES ('usage-fixture', 101, 'OPENAI', 'synthetic', 'SUMMARY', 'SUCCESS', CURRENT_TIMESTAMP(6))");
        execute(c, "INSERT INTO user_session (id, user_id, created_at, expires_at, last_seen_at, user_agent) VALUES ('session-fixture', 101, CURRENT_TIMESTAMP(6), '2030-01-01 00:00:00', CURRENT_TIMESTAMP(6), 'synthetic-browser')");
        assertDuplicateRejected(c, "INSERT INTO personal_ai_setting (id, owner_id, provider, model, base_url, encrypted_key, enabled, updated_at) VALUES ('duplicate-setting', 101, 'OPENAI', 'synthetic', 'https://example.invalid', 'synthetic-ciphertext', false, CURRENT_TIMESTAMP(6))");
        assertThatThrownBy(() -> execute(c, "INSERT INTO user_session (id, user_id, created_at, expires_at, last_seen_at) VALUES ('orphan-session', 999999, CURRENT_TIMESTAMP(6), '2030-01-01 00:00:00', CURRENT_TIMESTAMP(6))"))
                .isInstanceOf(SQLException.class).satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(1452));
        assertThat(scalar(c, "SELECT COUNT(*) FROM user_session WHERE user_id = 101")).isEqualTo("1");
        execute(c, "INSERT INTO vocabulary_book (id, owner_id, title, description, attribution, level, created_at) VALUES ('book-fixture', 101, 'Fixture book', '', '', 'A1', CURRENT_TIMESTAMP(6))");
        execute(c, "INSERT INTO vocabulary_word (id, book_id, term, ipa, pos, meaning, example_text, example_translation, distractors, sort_order) VALUES ('word-fixture', 'book-fixture', 'example', '', 'noun', '示例', '', '', '[]', 0)");
        execute(c, "INSERT INTO vocabulary_profile (owner_id, zone_id, daily_goal, selected_book_id, updated_at) VALUES (101, 'UTC', 10, 'book-fixture', CURRENT_TIMESTAMP(6))");
        execute(c, "INSERT INTO vocabulary_progress (id, owner_id, word_id, learning_correct, starred) VALUES ('progress-fixture', 101, 'word-fixture', 1, true)");
        execute(c, "INSERT INTO vocabulary_question (id, owner_id, word_id, book_id, mode, options_json, correct_option_id, created_at, expires_at) VALUES ('question-fixture', 101, 'word-fixture', 'book-fixture', 'LEARN', '[]', 'synthetic-option', CURRENT_TIMESTAMP(6), '2030-01-01 00:00:00')");
        execute(c, "INSERT INTO personal_recognition_result (id, owner_id, file_id, file_name, provider, model, task_type, result_text, input_tokens, output_tokens, created_at) VALUES ('recognition-fixture', 101, 'file-1', 'fixture.png', 'OPENAI', 'synthetic', 'GENERAL', 'Synthetic result', 1, 1, CURRENT_TIMESTAMP(6))");
        assertDuplicateRejected(c, "INSERT INTO vocabulary_progress (id, owner_id, word_id) VALUES ('duplicate-progress', 101, 'word-fixture')");
        assertThat(scalar(c, "SELECT COUNT(*) FROM vocabulary_progress WHERE owner_id = 101")).isEqualTo("1");
        assertThat(scalar(c, "SELECT COUNT(*) FROM personal_recognition_result WHERE owner_id = 101")).isEqualTo("1");
    }

    static void seedAllDomains(Connection c) throws SQLException {
        // Ordinary synthetic data only: no real credentials, payment details, or model calls.
        String[] statements = {
                "INSERT INTO app_role (id, code, name) VALUES (91, 'SCHEMA_TEST', '测试角色')",
                "INSERT INTO role_permission (role_id, permission_code) VALUES (91, 'experiment:read:own')",
                "INSERT INTO app_user (id, username, password_hash, display_name, email, status, role_id) VALUES (101, 'schema-test', 'synthetic-not-a-password', '测试用户', 'schema@example.invalid', 'ACTIVE', 91)",
                "INSERT INTO file_asset (id, original_name, stored_name, content_type, size_bytes, sha256, storage_path, owner_id, source, scan_status) VALUES ('file-1', '图像 🧪.png', 'file-1.png', 'image/png', 1024, REPEAT('a',64), 'fixture/file-1.png', 101, 'UPLOAD', 'CLEAN')",
                "INSERT INTO model_definition (id, code, name, version, provider, task_type, status) VALUES (301, 'fixture-model', '模型', '1', 'DEMO', 'LICENSE_PLATE', 'ACTIVE')",
                "INSERT INTO inference_task (id, trace_id, task_type, status, enhancement_enabled, input_file_id, model_id, requested_by, provider, cost_cny, baseline_result) VALUES ('task-1', 'trace-1', 'LICENSE_PLATE', 'COMPLETED', false, 'file-1', 301, 101, 'DEMO', 0.123456, '{\"prediction\":\"中文 🚗\"}')",
                "INSERT INTO user_wallet (user_id, balance_cny, monthly_quota_cny, quota_period_start) VALUES (101, 12.3456, 100, '2026-01-01')",
                "INSERT INTO wallet_ledger (user_id, type, amount, balance_after, reference_id) VALUES (101, 'USAGE', -0.1234, 12.3456, 'task-1')",
                "INSERT INTO recharge_order (id, user_id, method, amount, status, payment_token_hash, expires_at) VALUES ('recharge-1', 101, 'ALIPAY', 10.01, 'PENDING_PAYMENT', REPEAT('b',64), '2030-01-01 00:00:00')",
                "INSERT INTO provider_budget (provider, monthly_budget_cny, used_cny, period_start) VALUES ('DEMO', 500, 0.1234, '2026-01-01')",
                "INSERT INTO workspace (id, name, slug, color, owner_id) VALUES (201, '研究组', 'schema-workspace', '#112233', 101)",
                "INSERT INTO workspace_member (id, workspace_id, user_id, member_role) VALUES (401, 201, 101, 'OWNER')",
                "INSERT INTO workspace_member_permission (workspace_member_id, permission_code) VALUES (401, 'notes:read')",
                "INSERT INTO internal_message (id, sender_id, subject, body) VALUES ('message-1', 101, '测试消息', 'Markdown 正文 🧪')",
                "INSERT INTO message_recipient (message_id, recipient_id) VALUES ('message-1', 101)",
                "INSERT INTO message_attachment (message_id, file_id) VALUES ('message-1', 'file-1')",
                "INSERT INTO provider_credential (provider, label, encrypted_secret, fingerprint, created_by) VALUES ('DEMO', 'schema-fixture', 'synthetic-ciphertext-not-a-key', 'fixture', 101)",
                "INSERT INTO knowledge_topic (id, domain, name, builtin, owner_id) VALUES (501, '测试领域', '研究主题', false, 101)",
                "INSERT INTO knowledge_entry (id, topic_id, title, body, owner_id) VALUES ('entry-1', 501, '知识卡', '中文及 emoji 🧪', 101)",
                "INSERT INTO note (id, owner_id, title, body) VALUES ('note-1', 101, '多模态评测 🧪', '# 研究记录\\n中文与 emoji 保真')",
                "INSERT INTO note_reference (note_id, reference_type, reference_id) VALUES ('note-1', 'FILE', 'file-1')",
                "INSERT INTO note_share (id, note_id, shared_by, token) VALUES ('share-1', 'note-1', 101, '0123456789abcdef0123456789abcdef')"
        };
        for (String statement : statements) execute(c, statement);
    }

    private static final class TestDatabase implements AutoCloseable {
        private final String schema = "rv_schema_test_" + UUID.randomUUID().toString().replace("-", "");
        private final String username = System.getenv().getOrDefault("MYSQL_TEST_USERNAME", "root");
        private final String password = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
        private final String adminUrl;
        private final String url;

        private TestDatabase() throws SQLException {
            String configuredUrl = System.getenv("MYSQL_TEST_URL");
            int pathStart = configuredUrl.indexOf('/', "jdbc:mysql://".length());
            if (!configuredUrl.startsWith("jdbc:mysql://") || pathStart < 0) {
                throw new IllegalArgumentException("MYSQL_TEST_URL must be a single-server jdbc:mysql://host:port/database URL");
            }
            int queryStart = configuredUrl.indexOf('?', pathStart);
            String options = queryStart < 0 ? "" : configuredUrl.substring(queryStart);
            String server = configuredUrl.substring(0, pathStart + 1);
            adminUrl = server + options;
            url = server + schema + options;
            try (Connection c = DriverManager.getConnection(adminUrl, username, password)) {
                // No IF NOT EXISTS: never adopt or drop a database that this test did not create.
                execute(c, "CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
        }

        private Connection connect() throws SQLException { return DriverManager.getConnection(url, username, password); }

        private Flyway flyway(String target) {
            FluentConfiguration config = Flyway.configure().dataSource(url, username, password)
                    .locations("classpath:db/migration").cleanDisabled(true);
            if (target != null) config.target(target);
            return config.load();
        }

        private void validateHibernateMappings() throws ClassNotFoundException {
            var registry = new StandardServiceRegistryBuilder()
                    .applySetting("hibernate.connection.url", url)
                    .applySetting("hibernate.connection.username", username)
                    .applySetting("hibernate.connection.password", password)
                    .applySetting("hibernate.hbm2ddl.auto", "validate")
                    .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                    .applySetting("hibernate.jdbc.time_zone", "UTC")
                    .build();
            try {
                MetadataSources sources = new MetadataSources(registry);
                var scanner = new ClassPathScanningCandidateComponentProvider(false);
                scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
                var entities = scanner.findCandidateComponents("com.robustvision.platform.domain");
                assertThat(entities).hasSize(DOMAIN_TABLES.size() - 2);
                for (var definition : entities) sources.addAnnotatedClass(Class.forName(definition.getBeanClassName()));
                try (var factory = sources.buildMetadata().buildSessionFactory()) {
                    assertThat(factory.isOpen()).isTrue();
                }
            } finally {
                StandardServiceRegistryBuilder.destroy(registry);
            }
        }

        @Override public void close() throws SQLException {
            try (Connection c = DriverManager.getConnection(adminUrl, username, password)) {
                execute(c, "DROP DATABASE `" + schema + "`");
            }
        }
    }
}
