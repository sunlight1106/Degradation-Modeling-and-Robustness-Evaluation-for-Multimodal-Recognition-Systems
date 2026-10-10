package com.robustvision.platform.database;
import org.springframework.test.context.DynamicPropertyRegistry;
import java.sql.*;
import java.util.UUID;
/** Creates only a random test schema on the explicitly provided disposable server. */
public final class IsolatedMySql implements AutoCloseable {
 private final String adminUrl,username,password,schema;
 public IsolatedMySql()throws SQLException{
  String configured=System.getenv("MYSQL_TEST_URL");int slash=configured.indexOf('/',"jdbc:mysql://".length());int query=configured.indexOf('?',slash);String authority=configured.substring(0,slash+1),options=query<0?"":configured.substring(query);
  adminUrl=authority+options;username=System.getenv().getOrDefault("MYSQL_TEST_USERNAME","root");password=System.getenv().getOrDefault("MYSQL_TEST_PASSWORD","");schema="pkb_contract_"+UUID.randomUUID().toString().replace("-","");
  try(var c=DriverManager.getConnection(adminUrl,username,password);var s=c.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");}
 }
 public void properties(DynamicPropertyRegistry r){int q=adminUrl.indexOf('?');String url=(q<0?adminUrl:adminUrl.substring(0,q))+schema+(q<0?"":adminUrl.substring(q));r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->username);r.add("spring.datasource.password",()->password);}
 @Override public void close()throws SQLException{if(!schema.matches("pkb_contract_[a-f0-9]{32}"))throw new IllegalStateException();try(var c=DriverManager.getConnection(adminUrl,username,password);var s=c.createStatement()){s.execute("DROP DATABASE "+schema);}}
}
