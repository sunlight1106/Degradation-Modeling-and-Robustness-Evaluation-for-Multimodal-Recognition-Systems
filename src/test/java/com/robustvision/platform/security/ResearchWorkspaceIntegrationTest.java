package com.robustvision.platform.security;
import com.fasterxml.jackson.databind.*;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:research_suite;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","app.bootstrap.enabled=false","app.worker.enabled=false","app.rate-limit.enabled=false"})
@AutoConfigureMockMvc
class ResearchWorkspaceIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired UserRepository users;@Autowired RoleRepository roles;@Autowired PasswordEncoder passwords;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    String token,other,admin,ownerName;
    @BeforeEach void setup() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        RoleEntity role=roles.save(new RoleEntity("RESEARCH_"+suffix,"Writer","Tests",Set.of("note:read","note:write","knowledge:read","experiment:read","research:use","personal-ai:use","message:read","group:use","contacts:use","vocabulary:use")));
        token=login(ownerName="writer"+suffix,role);other=login("other"+suffix,role);
        RoleEntity ar=roles.findByCode("ADMIN").orElseGet(()->roles.save(new RoleEntity("ADMIN","Admin","Tests",role.getPermissions())));admin=login("admin"+suffix,ar);
    }
    String login(String name,RoleEntity role) throws Exception {
        users.save(new UserEntity(name,passwords.encode("TestResearch123!"),name,name+"@example.test",role));
        return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("username",name,"password","TestResearch123!")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data").path("token").asText();
    }
    ResultActions call(MockHttpServletRequestBuilder b,String auth,Object body) throws Exception {
        b.header("Authorization","Bearer "+auth);if(body!=null)b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));return mvc.perform(b);
    }
    JsonNode ok(MockHttpServletRequestBuilder b,Object body) throws Exception {return json.readTree(call(b,token,body).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");}
    String note() throws Exception{return ok(post("/api/v1/notes"),Map.of("title","Private lighthouse","body","original private content","library","计算机学习")).path("id").asText();}
    UserEntity owner(){return users.findByUsername(ownerName).orElseThrow();}

    @Test void searchMatchesAllTermsRanksTitlesAndReturnsOnlyBoundedExcerpts() throws Exception {
        String first=ok(post("/api/v1/notes"),Map.of("title","Alpha beta","body","x".repeat(1000)+" alpha beta "+"z".repeat(1000))).path("id").asText();
        String newer=ok(post("/api/v1/notes"),Map.of("title","Newest journal","body","beta and alpha are separate words")).path("id").asText();
        ok(post("/api/v1/notes"),Map.of("title","Alpha only","body","no second term"));
        jdbc.update("UPDATE note SET updated_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.parse("2020-01-01T00:00:00Z")),first);
        JsonNode found=ok(get("/api/v1/research/search").param("q","alpha beta"),null).path("items");
        assertThat(found.size()).isEqualTo(2);assertThat(found.get(0).path("id").asText()).isEqualTo(first);
        assertThat(found.get(0).path("excerpt").asText()).contains("alpha beta").hasSizeLessThanOrEqualTo(240);
        assertThat(ok(get("/api/v1/research/search").param("q","alpha beta").param("sort","recent"),null).path("items").get(0).path("id").asText()).isEqualTo(newer);
        call(get("/api/v1/research/search").param("type","INVALID"),token,null).andExpect(status().isBadRequest());
        call(get("/api/v1/research/search").param("sort","INVALID"),token,null).andExpect(status().isBadRequest());
        call(get("/api/v1/research/search").param("q","a b c d e f g h i j k"),token,null).andExpect(status().isBadRequest());
    }
    @Test void bookmarksFollowCurrentContentAndDoNotRetainRevokedPrivateText() throws Exception {
        String id=note();Map<String,String> ref=Map.of("kind","NOTE","id",id);
        ok(put("/api/v1/research/bookmarks"),ref);ok(put("/api/v1/research/bookmarks"),ref);
        assertThat(ok(get("/api/v1/research/bookmarks/keys"),null).size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM workspace_shortcut WHERE owner_id=? AND kind='BOOKMARK'",String.class,owner().getId())).isEmpty();
        for(String actor:List.of(other,admin)){
            call(put("/api/v1/research/bookmarks"),actor,ref).andExpect(status().isNotFound());
            call(get("/api/v1/research/bookmarks"),actor,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
            call(delete("/api/v1/research/bookmarks/NOTE/"+id),actor,null).andExpect(status().isOk());
        }
        ok(patch("/api/v1/notes/"+id),Map.of("title","Updated lighthouse","body","Latest content","baseRevision",0));
        JsonNode bookmark=ok(get("/api/v1/research/bookmarks"),null).path("items").get(0);
        assertThat(bookmark.path("title").asText()).isEqualTo("Updated lighthouse");assertThat(bookmark.path("excerpt").asText()).isEqualTo("Latest content");
        UserEntity user=owner();user.getPermissionOverrides().put("note:read",false);users.saveAndFlush(user);
        assertThat(ok(get("/api/v1/research/bookmarks"),null).path("items").size()).isZero();
        call(get("/api/v1/research/sources/NOTE/"+id),token,null).andExpect(status().isNotFound());
        assertThat(ok(post("/api/v1/research/bookmarks/cleanup"),null).asInt()).isEqualTo(1);
        assertThat(ok(get("/api/v1/research/bookmarks/keys"),null).size()).isZero();
    }
    @Test void savedSearchesDeduplicateFiltersAndRemainPrivate() throws Exception {
        Map<String,Object> filters=Map.of("q"," alpha   beta ","type","note","tag","","sort","recent");
        JsonNode saved=ok(post("/api/v1/research/saved-searches"),Map.of("name","My research","filters",filters));String id=saved.path("id").asText();
        JsonNode second=ok(post("/api/v1/research/saved-searches"),Map.of("name","Renamed","filters",Map.of("q","alpha beta","type","NOTE","tag","","sort","recent")));
        assertThat(second.path("id").asText()).isEqualTo(id);assertThat(second.path("filters").path("q").asText()).isEqualTo("alpha beta");
        for(String actor:List.of(other,admin)){
            call(get("/api/v1/research/saved-searches"),actor,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
            call(delete("/api/v1/research/saved-searches/"+id),actor,null).andExpect(status().isOk());
        }
        assertThat(ok(get("/api/v1/research/saved-searches"),null).size()).isEqualTo(1);
        call(post("/api/v1/research/saved-searches"),token,Map.of("name","Bad","filters",Map.of("type","invalid"))).andExpect(status().isBadRequest());
        ok(delete("/api/v1/research/saved-searches/"+id),null);assertThat(ok(get("/api/v1/research/saved-searches"),null).size()).isZero();
    }
    @Test void collectionCapsCannotBeBypassedByConcurrentDuplicateSaves() throws Exception {
        String id=note();var executor=java.util.concurrent.Executors.newFixedThreadPool(3);
        try{List<java.util.concurrent.Callable<Void>> tasks=new ArrayList<>();for(int i=0;i<3;i++)tasks.add(()->{call(put("/api/v1/research/bookmarks"),token,Map.of("kind","NOTE","id",id)).andExpect(status().isOk());return null;});for(var task:executor.invokeAll(tasks))task.get();}finally{executor.shutdownNow();}
        assertThat(ok(get("/api/v1/research/bookmarks/keys"),null).size()).isEqualTo(1);
        long owner=owner().getId();List<Object[]> rows=new ArrayList<>();for(int i=0;i<50;i++)rows.add(new Object[]{UUID.randomUUID().toString(),owner,"SEARCH","Saved "+i,"cap-"+i,"{}",java.sql.Timestamp.from(java.time.Instant.now())});
        jdbc.batchUpdate("INSERT INTO workspace_shortcut(id,owner_id,kind,title,resource_key,filters_json,created_at) VALUES (?,?,?,?,?,?,?)",rows);
        call(post("/api/v1/research/saved-searches"),token,Map.of("name","Over cap","filters",Map.of("q","unique"))).andExpect(status().isConflict());
    }
    @Test void deniedResearchPermissionAlsoClosesTheAiAnswerRoute() throws Exception {
        UserEntity user=owner();user.getPermissionOverrides().put("research:use",false);users.saveAndFlush(user);
        call(get("/api/v1/research/saved-searches"),token,null).andExpect(status().isForbidden());
        call(post("/api/v1/research/answers/preview"),token,Map.of("provider","DEEPSEEK","question","Why?","sources",List.of(Map.of("kind","NOTE","id","one")))).andExpect(status().isForbidden());
    }
    @Test void moduleDenialsApplyToSearchSourceAndSavedBookmarks() throws Exception {
        long owner=owner().getId();String file=UUID.randomUUID().toString(),result=UUID.randomUUID().toString(),message=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO file_asset(id,original_name,stored_name,content_type,size_bytes,sha256,storage_path,owner_id,source,scan_status,created_at) VALUES (?,'fixture.png','fixture','image/png',1,?,'fixture',?,'UPLOAD','CLEAN',CURRENT_TIMESTAMP)",file,"0".repeat(64),owner);
        jdbc.update("INSERT INTO personal_recognition_result(id,owner_id,file_id,file_name,provider,model,task_type,result_text,created_at) VALUES (?,?,?,'fixture.png','DEEPSEEK','fixture','RECEIPT','module-secret',CURRENT_TIMESTAMP)",result,owner,file);
        String slug="fixture-"+UUID.randomUUID();jdbc.update("INSERT INTO workspace(name,slug,color,owner_id,created_at,updated_at) VALUES ('Fixture',?,'#fff',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",slug,owner);
        Long group=jdbc.queryForObject("SELECT id FROM workspace WHERE slug=?",Long.class,slug);jdbc.update("INSERT INTO workspace_member(workspace_id,user_id,member_role,created_at) VALUES (?,?,'OWNER',CURRENT_TIMESTAMP)",group,owner);
        Long member=jdbc.queryForObject("SELECT id FROM workspace_member WHERE workspace_id=? AND user_id=?",Long.class,group,owner);jdbc.update("INSERT INTO workspace_member_permission(workspace_member_id,permission_code) VALUES (?,'CONTENT_READ')",member);
        jdbc.update("INSERT INTO internal_message(id,sender_id,subject,body,workspace_id,created_at) VALUES (?,?,'Group fixture','module-secret',?,CURRENT_TIMESTAMP)",message,owner,group);
        for(var ref:List.of(Map.of("kind","GROUP","id",message),Map.of("kind","RESULT","id",result)))ok(put("/api/v1/research/bookmarks"),ref);
        assertThat(ok(get("/api/v1/research/search").param("q","module-secret"),null).path("items").size()).isEqualTo(2);
        UserEntity user=owner();user.getPermissionOverrides().put("personal-ai:use",false);user.getPermissionOverrides().put("group:use",false);users.saveAndFlush(user);
        assertThat(ok(get("/api/v1/research/search").param("q","module-secret"),null).path("items").size()).isZero();
        assertThat(ok(get("/api/v1/research/bookmarks"),null).path("items").size()).isZero();
        call(get("/api/v1/research/sources/RESULT/"+result),token,null).andExpect(status().isNotFound());call(get("/api/v1/research/sources/GROUP/"+message),token,null).andExpect(status().isNotFound());
    }

    @Test void versionsTrashRestoreAndPurgePreservePrivacyAndRevocation() throws Exception {
        String id=note();String share=ok(post("/api/v1/notes/"+id+"/shares"),Map.of()).path("token").asText();
        ok(patch("/api/v1/notes/"+id),Map.of("body","new content","baseRevision",0));
        JsonNode version=ok(get("/api/v1/notes/"+id+"/versions"),null).get(0);
        assertThat(version.path("revision").asLong()).isZero();
        for(String actor:List.of(other,admin)){
            call(get("/api/v1/notes/"+id+"/versions"),actor,null).andExpect(status().isNotFound());
            call(post("/api/v1/notes/"+id+"/versions/"+version.path("id").asText()+"/restore"),actor,Map.of("baseRevision",1)).andExpect(status().isNotFound());
        }
        String restore="/api/v1/notes/"+id+"/versions/"+version.path("id").asText()+"/restore";
        call(post(restore),token,Map.of("baseRevision",0)).andExpect(status().isConflict());
        ok(post(restore),Map.of("baseRevision",1));
        assertThat(ok(get("/api/v1/notes/"+id),null).path("body").asText()).isEqualTo("original private content");
        ok(delete("/api/v1/notes/"+id),null);
        call(get("/api/v1/notes/"+id),token,null).andExpect(status().isNotFound());
        assertThat(ok(get("/api/v1/notes/trash"),null).toString()).contains(id);
        assertThat(ok(get("/api/v1/research/search").param("q","lighthouse"),null).path("items").size()).isZero();
        call(post("/api/v1/notes/"+id+"/restore"),other,null).andExpect(status().isNotFound());
        ok(post("/api/v1/notes/"+id+"/restore"),null);
        call(get("/api/v1/notes/shared/"+share),other,null).andExpect(status().isGone());
        assertThat(ok(get("/api/v1/notes/"+id+"/versions"),null).size()).isGreaterThanOrEqualTo(2);
        call(delete("/api/v1/notes/"+id+"/purge"),token,null).andExpect(status().isNotFound());
        ok(delete("/api/v1/notes/"+id),null);ok(delete("/api/v1/notes/"+id+"/purge"),null);
        call(post("/api/v1/notes/"+id+"/restore"),token,null).andExpect(status().isNotFound());
    }
    @Test void searchDoesNotLetAdminsReadPrivateContentAndEscapesWildcards() throws Exception {
        String id=note();assertThat(ok(get("/api/v1/research/search").param("q","lighthouse"),null).path("items").size()).isEqualTo(1);
        for(String actor:List.of(other,admin)){
            call(get("/api/v1/research/search").param("q","lighthouse"),actor,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
            call(get("/api/v1/research/sources/NOTE/"+id),actor,null).andExpect(status().isNotFound());
        }
        assertThat(ok(get("/api/v1/research/search").param("q","%"),null).path("items").size()).isZero();
        call(get("/api/v1/research/search").param("page","-1"),token,null).andExpect(status().isBadRequest());
    }
    @Test void cardsUseServerScheduleAndRejectStaleOrForeignChanges() throws Exception {
        String id=note();Map<String,Object> card=Map.of("title","Question","revision",0,"data",Map.of("question","Why?","answer","Because","sourceKind","NOTE","sourceId",id,"stage",99,"due","2100-01-01T00:00:00Z"));
        JsonNode saved=ok(post("/api/v1/research/records/CARD"),card);String cid=saved.path("id").asText();
        assertThat(saved.path("data").path("stage").asInt()).isZero();
        call(get("/api/v1/research/records/"+cid),other,null).andExpect(status().isNotFound());
        JsonNode reviewed=ok(post("/api/v1/research/records/"+cid+"/grade"),Map.of("grade",0,"revision",0));
        assertThat(reviewed.path("data").path("mistakes").asInt()).isEqualTo(1);
        call(post("/api/v1/research/records/"+cid+"/grade"),token,Map.of("grade",3,"revision",0)).andExpect(status().isConflict());
        call(post("/api/v1/research/records/CARD"),other,card).andExpect(status().isNotFound());
    }
    @Test void collectionsValidateFieldsAndKeepOptimisticRevisions() throws Exception {
        Map<String,Object> data=Map.of("fields",List.of(Map.of("name","Due","type","date")),"rows",List.of(Map.of("id","one","title","Chapter","values",Map.of("Due","2026-10-08"))));
        JsonNode saved=ok(post("/api/v1/research/records/COLLECTION"),Map.of("title","Plan","revision",0,"data",data));
        String path="/api/v1/research/records/COLLECTION/"+saved.path("id").asText();
        ok(put(path),Map.of("title","Revised","revision",0,"data",data));
        call(put(path),token,Map.of("title","Stale","revision",0,"data",data)).andExpect(status().isConflict());
        call(put(path),other,Map.of("title","Foreign","revision",1,"data",data)).andExpect(status().isNotFound());
    }
    @Test void benchmarkCountsMissingPredictionsWithoutPretendingUnknownCostIsZero() throws Exception {
        List<Map<String,Object>> rows=List.of(Map.of("sample","1","expected","cat","model","A","version","v1","output","cat","status","OK"),Map.of("sample","2","expected","dog","model","A","version","v1","output","dog","status","OK"),Map.of("sample","1","expected","cat","model","B","version","v1","output","cut","status","OK"));
        JsonNode report=ok(post("/api/v1/research/evaluations"),Map.of("title","Comparison","parameters","fixture","provenance","DEMO","rows",rows));
        JsonNode a=report.path("data").path("metrics").get(0),b=report.path("data").path("metrics").get(1);
        assertThat(a.path("accuracy").asDouble()).isEqualTo(1);assertThat(a.path("knownCostCny").isNull()).isTrue();
        assertThat(b.path("failed").asInt()).isEqualTo(1);assertThat(b.path("characterErrorRate").asDouble()).isCloseTo(4.0/6,within(.000001));
        call(get("/api/v1/research/evaluations"),other,null).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }
}
