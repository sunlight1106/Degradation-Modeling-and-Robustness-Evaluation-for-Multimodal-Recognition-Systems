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

/** Reuses the API, account lifecycle and ownership contracts on an isolated schema. */
@Tag("mysql") @EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches="jdbc:mysql:.*")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","app.bootstrap.enabled=false","app.worker.enabled=false","app.rate-limit.enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PersonalWorkflowMySqlIntegrationTest extends PersonalWorkflowIntegrationTest {
 private static String adminUrl,url,schema;
 private static final String username=System.getenv().getOrDefault("MYSQL_TEST_USERNAME","root"),password=System.getenv().getOrDefault("MYSQL_TEST_PASSWORD","");
 @DynamicPropertySource static void mysql(DynamicPropertyRegistry properties)throws SQLException{
  String configured=System.getenv("MYSQL_TEST_URL");int path=configured.indexOf('/',"jdbc:mysql://".length());
  if(path<0||!configured.startsWith("jdbc:mysql://"))throw new IllegalArgumentException("A single MySQL server is required");
  int query=configured.indexOf('?',path);String options=query<0?"":configured.substring(query),server=configured.substring(0,path+1);
  schema="rv_workflow_test_"+UUID.randomUUID().toString().replace("-","");adminUrl=server+options;url=server+schema+options;
  try(var c=DriverManager.getConnection(adminUrl,username,password);var s=c.createStatement()){s.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");}
  properties.add("spring.datasource.url",()->url);properties.add("spring.datasource.username",()->username);properties.add("spring.datasource.password",()->password);
 }
 @AfterAll static void dropOwnedSchema()throws SQLException{if(schema!=null)try(var c=DriverManager.getConnection(adminUrl,username,password);var s=c.createStatement()){s.execute("DROP DATABASE `"+schema+"`");}}
}
