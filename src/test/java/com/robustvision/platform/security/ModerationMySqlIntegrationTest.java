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

/** Run the same moderation and ownership contracts on a disposable MySQL schema. */
@Tag("mysql") @EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches="jdbc:mysql:.*")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","app.bootstrap.enabled=false","app.worker.enabled=false","app.rate-limit.enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ModerationMySqlIntegrationTest extends ModerationIntegrationTest {
 private static String server,schema;
 private static final String username=System.getenv().getOrDefault("MYSQL_TEST_USERNAME","root"),password=System.getenv().getOrDefault("MYSQL_TEST_PASSWORD","");
 @DynamicPropertySource static void mysql(DynamicPropertyRegistry p)throws SQLException{
  String url=System.getenv("MYSQL_TEST_URL");int path=url.indexOf('/',"jdbc:mysql://".length()),query=url.indexOf('?',path);
  server=url.substring(0,path+1)+(query<0?"":url.substring(query));schema="rv_moderation_test_"+UUID.randomUUID().toString().replace("-","");
  try(var c=DriverManager.getConnection(server,username,password);var s=c.createStatement()){s.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");}
  String owned=url.substring(0,path+1)+schema+(query<0?"":url.substring(query));p.add("spring.datasource.url",()->owned);p.add("spring.datasource.username",()->username);p.add("spring.datasource.password",()->password);
 }
 @AfterAll static void cleanup()throws SQLException{if(schema!=null)try(var c=DriverManager.getConnection(server,username,password);var s=c.createStatement()){s.execute("DROP DATABASE `"+schema+"`");}}
}
