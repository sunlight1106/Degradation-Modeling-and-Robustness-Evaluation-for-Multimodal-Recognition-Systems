package com.robustvision.platform.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.*;
import java.util.UUID;

/** Reuse the actual auth/permission/statistics contract against a migrated MySQL database. */
@Tag("mysql")
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = "jdbc:mysql:.*")
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "app.bootstrap.enabled=false", "app.worker.enabled=false", "app.rate-limit.enabled=false"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdministrationMySqlIntegrationTest extends AdministrationIntegrationTest {
    private static String adminUrl, url, schema;
    private static final String username = System.getenv().getOrDefault("MYSQL_TEST_USERNAME", "root");
    private static final String password = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
    private static boolean created;
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry properties) throws SQLException {
        if (!created) {
            String configured = System.getenv("MYSQL_TEST_URL");
            int path = configured.indexOf('/', "jdbc:mysql://".length());
            if (path < 0 || !configured.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("MYSQL_TEST_URL must name a single MySQL server");
            int query = configured.indexOf('?', path);
            String options = query < 0 ? "" : configured.substring(query), server = configured.substring(0, path + 1);
            schema = "rv_admin_test_" + UUID.randomUUID().toString().replace("-", ""); adminUrl = server + options; url = server + schema + options;
            try (var connection = DriverManager.getConnection(adminUrl, username, password); var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); created = true;
            }
        }
        properties.add("spring.datasource.url", () -> url); properties.add("spring.datasource.username", () -> username); properties.add("spring.datasource.password", () -> password);
    }
    @AfterAll static void dropOnlyOwnedTestSchema() throws SQLException {
        if (created) try (var connection = DriverManager.getConnection(adminUrl, username, password); var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + schema + "`"); created = false;
        }
    }
}
