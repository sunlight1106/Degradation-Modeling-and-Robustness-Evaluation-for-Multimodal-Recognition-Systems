package com.robustvision.platform.security;
import com.robustvision.platform.database.IsolatedMySql;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
@Tag("mysql") @EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches="jdbc:mysql:.*")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","app.bootstrap.enabled=false","app.worker.enabled=false","app.rate-limit.enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class GroupLifecycleMySqlIntegrationTest extends GroupLifecycleIntegrationTest {
 static IsolatedMySql fixture;
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r)throws Exception{if(fixture==null)fixture=new IsolatedMySql();fixture.properties(r);}
 @AfterAll static void cleanup()throws Exception{if(fixture!=null)fixture.close();}
}
