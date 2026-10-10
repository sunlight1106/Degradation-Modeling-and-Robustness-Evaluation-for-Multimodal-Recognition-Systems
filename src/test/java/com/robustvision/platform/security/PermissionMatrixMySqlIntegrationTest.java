package com.robustvision.platform.security;

import com.robustvision.platform.database.IsolatedMySql;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.SQLException;

@Tag("mysql")
@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches="jdbc:mysql:.*")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","app.bootstrap.enabled=false","app.worker.enabled=false","app.rate-limit.enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PermissionMatrixMySqlIntegrationTest extends PermissionMatrixIntegrationTest {
    static IsolatedMySql database;
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry registry) throws SQLException {
        if(database==null)database=new IsolatedMySql();
        database.properties(registry);
    }
    @AfterAll static void cleanup() throws SQLException { if(database!=null)database.close(); }
}
