package com.robustvision.platform.security;

import com.robustvision.platform.service.CurrentUserService;
import com.robustvision.platform.service.KnowledgeSearchService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated real database: never migrates or benchmarks the user's data. */
@Tag("mysql") @EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches="jdbc:mysql:.*")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","app.bootstrap.enabled=false","app.worker.enabled=false","app.rate-limit.enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ResearchWorkspaceMySqlIntegrationTest extends ResearchWorkspaceIntegrationTest {
    private static String adminUrl,url,schema;
    private static final String username=System.getenv().getOrDefault("MYSQL_TEST_USERNAME","root"),password=System.getenv().getOrDefault("MYSQL_TEST_PASSWORD","");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws SQLException {
        String configured=System.getenv("MYSQL_TEST_URL");int path=configured.indexOf('/',"jdbc:mysql://".length()),query=configured.indexOf('?',path);
        String options=query<0?"":configured.substring(query),server=configured.substring(0,path+1);
        schema="rv_search_test_"+UUID.randomUUID().toString().replace("-","");adminUrl=server+options;url=server+schema+options;
        try(var connection=DriverManager.getConnection(adminUrl,username,password);var statement=connection.createStatement()){statement.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");}
        properties.add("spring.datasource.url",()->url);properties.add("spring.datasource.username",()->username);properties.add("spring.datasource.password",()->password);
    }
    @AfterAll static void cleanup() throws SQLException {if(schema!=null)try(var connection=DriverManager.getConnection(adminUrl,username,password);var statement=connection.createStatement()){statement.execute("DROP DATABASE `"+schema+"`");}}
    @Test void measureBoundedSearchAgainstThePreviousFullBodyTransfer() {
        var user=owner();String body="benchneedle "+"a".repeat(199988);List<Object[]> fixture=new ArrayList<>();
        for(int i=0;i<32;i++)fixture.add(new Object[]{UUID.randomUUID().toString(),user.getId(),"benchneedle "+i,body,"DRAFT","综合学习","MARKDOWN",java.sql.Timestamp.from(java.time.Instant.now()),java.sql.Timestamp.from(java.time.Instant.now())});
        jdbc.batchUpdate("INSERT INTO note(id,owner_id,title,body,status,library,content_format,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",fixture);
        var current=mock(CurrentUserService.class);when(current.requireCurrent()).thenReturn(user);when(current.hasPermission(user,"note:read")).thenReturn(true);
        var service=new KnowledgeSearchService(jdbc,current);
        java.util.function.Supplier<List<String>> before=()->jdbc.query("SELECT body FROM note WHERE owner_id=? AND deleted_at IS NULL AND (LOWER(title) LIKE ? OR LOWER(body) LIKE ? OR LOWER(COALESCE(tags,'')) LIKE ?) ORDER BY updated_at DESC,id LIMIT 31",(r,i)->r.getString(1),user.getId(),"%benchneedle%","%benchneedle%","%benchneedle%");
        java.util.function.Supplier<KnowledgeSearchService.Page> after=()->service.search("benchneedle","NOTE","",null,null,0);
        for(int i=0;i<2;i++){before.get();after.get();}
        List<Double> oldTimes=new ArrayList<>(),newTimes=new ArrayList<>();for(int i=0;i<10;i++){if(i%2==0){oldTimes.add(time(before));newTimes.add(time(after));}else{newTimes.add(time(after));oldTimes.add(time(before));}}
        long oldChars=before.get().stream().mapToLong(String::length).sum(),newChars=after.get().items().stream().mapToLong(h->h.excerpt().length()).sum();
        assertThat(after.get().items()).hasSize(30);assertThat(after.get().hasMore()).isTrue();assertThat(newChars).isLessThanOrEqualTo(7200);assertThat(oldChars).isEqualTo(6_200_000);
        Collections.sort(oldTimes);Collections.sort(newTimes);
        System.out.printf(Locale.ROOT,"SEARCH_BENCHMARK oldMedianMs=%.2f newMedianMs=%.2f oldP95Ms=%.2f newP95Ms=%.2f oldJdbcCharacters=%d newReturnedExcerptCharacters=%d samples=10 bodyCharacters=200000%n",median(oldTimes),median(newTimes),oldTimes.get(9),newTimes.get(9),oldChars,newChars);
    }
    private double time(java.util.function.Supplier<?> operation){long start=System.nanoTime();operation.get();return (System.nanoTime()-start)/1_000_000.0;}
    private double median(List<Double> rows){return (rows.get(4)+rows.get(5))/2;}
}
