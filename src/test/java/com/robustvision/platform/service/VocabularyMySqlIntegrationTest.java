package com.robustvision.platform.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlMergeMode;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** Runs the SAME API/ownership/concurrency/DST contract on a freshly migrated real MySQL schema. */
@Tag("mysql")
@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches="jdbc:mysql:.*")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","app.bootstrap.enabled=false","app.rate-limit.enabled=false"})
@SqlMergeMode(SqlMergeMode.MergeMode.OVERRIDE)
@Sql(statements="SELECT 1",executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class VocabularyMySqlIntegrationTest extends VocabularyIntegrationTest {
    private static String adminUrl, url, schema;
    private static final String username=System.getenv().getOrDefault("MYSQL_TEST_USERNAME","root");
    private static final String password=System.getenv().getOrDefault("MYSQL_TEST_PASSWORD","");
    private static boolean created;
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry properties) throws SQLException {
        if(!created) {
            String configured=System.getenv("MYSQL_TEST_URL");
            int path=configured.indexOf('/',"jdbc:mysql://".length());
            if(path<0||!configured.startsWith("jdbc:mysql://"))throw new IllegalArgumentException("MYSQL_TEST_URL must name a single MySQL server");
            int query=configured.indexOf('?',path);String options=query<0?"":configured.substring(query);String server=configured.substring(0,path+1);
            schema="rv_vocab_test_"+UUID.randomUUID().toString().replace("-","");adminUrl=server+options;url=server+schema+options;
            try(var connection=DriverManager.getConnection(adminUrl,username,password);var statement=connection.createStatement()) {
                statement.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");created=true;
            }
        }
        properties.add("spring.datasource.url",()->url);properties.add("spring.datasource.username",()->username);properties.add("spring.datasource.password",()->password);
    }
    @AfterAll static void dropOnlyOwnedTestSchema() throws SQLException {
        if(created)try(var connection=DriverManager.getConnection(adminUrl,username,password);var statement=connection.createStatement()) {
            statement.execute("DROP DATABASE `"+schema+"`");created=false;
        }
    }
}
