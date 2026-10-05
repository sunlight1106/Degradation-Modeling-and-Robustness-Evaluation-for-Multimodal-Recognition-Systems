package com.robustvision.platform.database;

import com.robustvision.platform.service.SecretEncryptionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in real restore rehearsal. Creates/drops only its own random synthetic schemas. */
@Tag("mysql")
@EnabledIfEnvironmentVariable(named = "MYSQL_BACKUP_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = "jdbc:mysql:.*")
class MySqlBackupRestoreTest {
    private static final String SYNTHETIC_MASTER = "synthetic-test-only-master-0123456789abcdef";
    private static final String SYNTHETIC_TOKEN = "synthetic-provider-token-never-a-real-key";

    @Test
    void fullFlywayDatabaseAndFilesystemRestorePreservesEveryRowAndEncryptedSecrets() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String source = "rv_backup_test_" + suffix;
        String restored = "rv_restore_" + suffix;
        String configured = System.getenv("MYSQL_TEST_URL");
        URI endpoint = URI.create(configured.substring("jdbc:".length()));
        assertThat(endpoint.getHost()).isIn("127.0.0.1", "::1");
        assertThat(endpoint.getUserInfo()).isNull();
        String server = "jdbc:mysql://" + endpoint.getRawAuthority() + "/";
        String options = endpoint.getRawQuery() == null ? "" : "?" + endpoint.getRawQuery();
        String username = System.getenv().getOrDefault("MYSQL_TEST_USERNAME", "root");
        String password = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
        Path temporary = Files.createTempDirectory("rv-backup-rehearsal-");
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwx------"));
        boolean createdSource = false;
        boolean createdRestore = false;
        try (Connection admin = DriverManager.getConnection(server + options, username, password)) {
            assertThat(admin.getMetaData().getDatabaseProductVersion()).startsWith("8.4.");
            // No IF NOT EXISTS: never adopt an existing schema.
            execute(admin, "CREATE DATABASE `" + source + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            createdSource = true;
            Flyway sourceFlyway = flyway(server + source + options, username, password);
            assertThat(sourceFlyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(16);
            sourceFlyway.validate();
            Path sourceFiles = Files.createDirectory(temporary.resolve("source-files"));
            Path binary = sourceFiles.resolve("fixture/图像 🧪.bin");
            Files.createDirectories(binary.getParent());
            byte[] content = new byte[]{0, 1, 2, 13, 10, (byte) 255, (byte) 128, 42};
            Files.write(binary, content);
            Path text = sourceFiles.resolve("fixture/笔记.txt");
            Files.writeString(text, "文件备份测试 🧪\nSecond line\r\n", StandardCharsets.UTF_8);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            SecretEncryptionService crypto = new SecretEncryptionService(SYNTHETIC_MASTER);
            String ciphertext = crypto.encrypt(SYNTHETIC_TOKEN);
            try (Connection c = DriverManager.getConnection(server + source + options, username, password)) {
                MySqlSchemaMigrationTest.seedAllDomains(c);
                execute(c, "INSERT INTO app_user (id,username,password_hash,display_name,email,status,role_id) VALUES (102,'social-fixture','synthetic','Friend','friend@example.invalid','ACTIVE',91)");
                execute(c, "INSERT INTO contact_link (id,low_user_id,high_user_id,requester_id,status) VALUES (301,101,102,101,'ACCEPTED')");
                execute(c, "INSERT INTO chat_message (contact_id,sender_id,client_id,body) VALUES (301,101,'00000000-0000-0000-0000-000000000001','恢复后仍可读取的私聊')");
                execute(c, "UPDATE note SET revision=7 WHERE id='note-1'");
                execute(c, "UPDATE note SET library = '英语学习', content_format = 'HTML', body = '<h2>Reading</h2><p>学习记录</p>' WHERE id = 'note-1'");
                execute(c, "INSERT INTO note (id, owner_id, title, body, parent_id) VALUES ('note-child',101,'子页面','代码笔记','note-1')");
                MySqlSchemaMigrationTest.seedAccountSettings(c);
                execute(c, "INSERT INTO personal_ai_memory (id,owner_id,title,body,enabled,revision,created_at,updated_at) VALUES ('memory-fixture',101,'学习偏好','恢复后的私人记忆',true,2,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))");
                execute(c, "UPDATE internal_message SET workspace_id=201 WHERE id='message-1'");
                execute(c, "INSERT INTO internal_message (id,sender_id,subject,body,workspace_id,reply_to_id) VALUES ('group-reply',101,'群回复','回复资料',201,'message-1')");
                try (var statement = c.prepareStatement("UPDATE file_asset SET storage_path=?, size_bytes=?, sha256=? WHERE id='file-1'")) {
                    statement.setString(1, "fixture/图像 🧪.bin");
                    statement.setLong(2, content.length);
                    statement.setString(3, sha);
                    assertThat(statement.executeUpdate()).isOne();
                }
                try (var statement = c.prepareStatement("UPDATE provider_credential SET encrypted_secret=? WHERE label='schema-fixture'")) {
                    statement.setString(1, ciphertext);
                    assertThat(statement.executeUpdate()).isOne();
                }
                try (var statement = c.prepareStatement("UPDATE personal_ai_setting SET encrypted_key=? WHERE id='setting-fixture'")) {
                    statement.setString(1, ciphertext);
                    assertThat(statement.executeUpdate()).isOne();
                }
            }
            Path config = temporary.resolve("client.cnf");
            Files.writeString(config, "[client]\nhost=" + endpoint.getHost() + "\nport=" + (endpoint.getPort() < 0 ? 3306 : endpoint.getPort())
                    + "\nuser=\"" + configEscape(username) + "\"\npassword=\"" + configEscape(password) + "\"\n");
            Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
            Path archive = temporary.resolve("synthetic-backup.zip");
            Path restoredFiles = temporary.resolve("restored-files");
            run(config, 0, "backup", "--writers-stopped", "--schema", source,
                    "--files-root", sourceFiles.toString(), "--output", archive.toString());
            assertThat(Files.getPosixFilePermissions(archive)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                assertThat(zip.stream().map(entry -> entry.getName()).toList()).containsExactlyInAnyOrder(
                        "manifest.json", "database.sql", "files/fixture/图像 🧪.bin", "files/fixture/笔记.txt");
                String dump = new String(zip.getInputStream(zip.getEntry("database.sql")).readAllBytes(), StandardCharsets.UTF_8);
                assertThat(dump).contains(ciphertext).doesNotContain(SYNTHETIC_MASTER, SYNTHETIC_TOKEN, "CREATE DATABASE", "USE `");
            }
            run(config, 0, "restore", "--trusted-local-archive", "--archive", archive.toString(),
                    "--target-schema", restored, "--files-target", restoredFiles.toString());
            createdRestore = true;
            Flyway restoredFlyway = flyway(server + restored + options, username, password);
            restoredFlyway.validate();
            assertThat(restoredFlyway.migrate().migrationsExecuted).isZero();
            assertThat(Files.readAllBytes(restoredFiles.resolve("fixture/图像 🧪.bin"))).isEqualTo(content);
            assertThat(Files.readAllBytes(restoredFiles.resolve("fixture/笔记.txt"))).isEqualTo(Files.readAllBytes(text));
            try (Connection c = DriverManager.getConnection(server + restored + options, username, password)) {
                assertThat(scalar(c, "SELECT body FROM personal_ai_memory WHERE owner_id=101")).isEqualTo("恢复后的私人记忆");
                assertThat(scalar(c, "SELECT revision FROM personal_ai_memory WHERE owner_id=101")).isEqualTo("2");
                assertThat(scalar(c, "SELECT body FROM chat_message WHERE contact_id=301")).isEqualTo("恢复后仍可读取的私聊");
                assertThat(scalar(c, "SELECT status FROM contact_link WHERE id=301")).isEqualTo("ACCEPTED");
                assertThat(scalar(c, "SELECT revision FROM note WHERE id='note-1'")).isEqualTo("7");
                assertThat(scalar(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")).isEqualTo("35");
                assertThat(scalar(c, "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE()")).isEqualTo("49");
                assertThat(scalar(c, "SELECT workspace_id FROM internal_message WHERE id='group-reply'")).isEqualTo("201");
                assertThat(scalar(c, "SELECT reply_to_id FROM internal_message WHERE id='group-reply'")).isEqualTo("message-1");
                assertThat(scalar(c, "SELECT parent_id FROM note WHERE id='note-child'")).isEqualTo("note-1");
                assertThat(scalar(c, "SELECT title FROM note WHERE id='note-1'")).isEqualTo("多模态评测 🧪");
                assertThat(scalar(c, "SELECT balance_cny FROM user_wallet WHERE user_id=101")).isEqualTo("12.3456");
                assertThat(scalar(c, "SELECT cost_cny FROM inference_task WHERE id='task-1'")).isEqualTo("0.123456");
                assertThat(scalar(c, "SELECT encrypted_secret FROM provider_credential WHERE label='schema-fixture'")).isEqualTo(ciphertext);
                assertThat(crypto.decrypt(scalar(c, "SELECT encrypted_key FROM personal_ai_setting WHERE id='setting-fixture'"))).isEqualTo(SYNTHETIC_TOKEN);
                assertThatThrownBy(() -> execute(c, "INSERT INTO note (id, owner_id, title, body) VALUES ('orphan', 999999, 'invalid', '')"))
                        .isInstanceOf(SQLException.class).satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(1452));
                assertThatThrownBy(() -> execute(c, "INSERT INTO workspace_member (workspace_id,user_id,member_role) VALUES (201,101,'MEMBER')"))
                        .isInstanceOf(SQLException.class).satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(1062));
            }
            // Actual command refusal must preserve restored database and files.
            run(config, 1, "restore", "--trusted-local-archive", "--archive", archive.toString(),
                    "--target-schema", restored, "--files-target", temporary.resolve("other-files").toString());
            assertThat(Files.exists(temporary.resolve("other-files"))).isFalse();
            run(config, 1, "backup", "--writers-stopped", "--schema", source,
                    "--files-root", sourceFiles.toString(), "--output", archive.toString());
            assertThat(Files.readAllBytes(restoredFiles.resolve("fixture/图像 🧪.bin"))).isEqualTo(content);
        } finally {
            try (Connection admin = DriverManager.getConnection(server + options, username, password)) {
                // This test owns these random names. No application/existing database is touched.
                if (createdRestore) execute(admin, "DROP DATABASE IF EXISTS `" + restored + "`");
                if (createdSource) execute(admin, "DROP DATABASE `" + source + "`");
            } finally {
                try (var paths = Files.walk(temporary)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    private static Flyway flyway(String url, String username, String password) {
        return Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration").cleanDisabled(true).load();
    }

    private static String configEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static void run(Path config, int expectedStatus, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("python3", "scripts/backup/local_backup.py", "--local-dev", "--defaults-file", config.toString(),
                "--mysql", System.getenv().getOrDefault("MYSQL_CLIENT_PATH", "mysql"),
                "--mysqldump", System.getenv().getOrDefault("MYSQL_DUMP_PATH", "mysqldump")));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean completed = process.waitFor(180, TimeUnit.SECONDS);
        if (!completed) process.destroyForcibly().waitFor();
        String report = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(completed).as("Recovery command completed").isTrue();
        assertThat(process.exitValue()).as("Recovery command result: %s", report).isEqualTo(expectedStatus);
        if (expectedStatus == 0) assertThat(report).contains("_verified");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
