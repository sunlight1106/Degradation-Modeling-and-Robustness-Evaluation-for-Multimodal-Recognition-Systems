package com.robustvision.platform.security;

import com.fasterxml.jackson.databind.*;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.*;
import com.robustvision.platform.service.AccountLifecycleService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:personal_workflows;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","app.bootstrap.enabled=false","app.worker.enabled=false","app.rate-limit.enabled=false"})
@AutoConfigureMockMvc @Import(GroupCommunicationIntegrationTest.FastPasswords.class)
class PersonalWorkflowIntegrationTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired UserRepository users;@Autowired RoleRepository roles;@Autowired PasswordEncoder passwords;@Autowired JdbcTemplate db;@Autowired AccountLifecycleService lifecycle;
 UserEntity user,other;String token,otherToken;Instant now;static final String PASSWORD="SyntheticWorkflow123!";
 @Autowired com.robustvision.platform.service.PersonalAiRateLimiter aiLimits;
 @Autowired com.robustvision.platform.service.PersonalAiPersistenceService aiPersistence;
 @BeforeEach void setup()throws Exception{String suffix=UUID.randomUUID().toString().substring(0,8);var role=roles.save(new RoleEntity("WF_"+suffix,"Synthetic","Synthetic",Set.of("note:read","note:write","workspace:manage","group:use","message:read")));user=users.save(new UserEntity("wf"+suffix,passwords.encode(PASSWORD),"Same name","wf"+suffix+"@example.invalid",role));other=users.save(new UserEntity("other"+suffix,passwords.encode(PASSWORD),"Same name","other"+suffix+"@example.invalid",role));token=login(user.getUsername());otherToken=login(other.getUsername());now=Instant.now();ReflectionTestUtils.setField(lifecycle,"clock",Clock.fixed(now,ZoneOffset.UTC));}
 @AfterEach void clock(){ReflectionTestUtils.setField(lifecycle,"clock",Clock.systemUTC());}
 JsonNode data(MvcResult r)throws Exception{return mapper.readTree(r.getResponse().getContentAsByteArray()).path("data");}
 ResultActions call(MockHttpServletRequestBuilder r,String auth,Object body)throws Exception{if(auth!=null)r.header("Authorization","Bearer "+auth);if(body!=null)r.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));return mvc.perform(r);}
 String login(String identifier)throws Exception{return data(call(post("/api/v1/auth/login"),null,Map.of("username",identifier,"password",PASSWORD)).andExpect(status().isOk()).andReturn()).path("token").asText();}
 JsonNode note(String auth,String title,String body)throws Exception{return data(call(post("/api/v1/notes"),auth,Map.of("title",title,"body",body,"library","研究","contentFormat","MARKDOWN")).andExpect(status().isOk()).andReturn());}
 JsonNode prepare(String mode)throws Exception{return data(call(post("/api/v1/account/closure/prepare"),token,Map.of("mode",mode,"currentPassword",PASSWORD)).andExpect(status().isOk()).andReturn());}
 Map<String,Object> confirm(JsonNode ticket){return Map.of("token",ticket.path("token").asText(),"currentPassword",PASSWORD,"confirmation",ticket.path("mode").asText().equals("PURGE")?"永久注销":"停用账号");}
 void advance(){ReflectionTestUtils.setField(lifecycle,"clock",Clock.fixed(now.plusSeconds(6),ZoneOffset.UTC));}
 @Test void suspensionRequiresServerDelayAndCannotBeConfirmedByAnotherOwner()throws Exception{
  var ticket=prepare("SUSPEND");call(post("/api/v1/account/closure/confirm"),token,confirm(ticket)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("CLOSURE_WAIT"));advance();
  call(post("/api/v1/account/closure/confirm"),otherToken,confirm(ticket)).andExpect(status().isBadRequest());
  call(post("/api/v1/account/closure/confirm"),token,confirm(ticket)).andExpect(status().isOk());call(get("/api/v1/auth/me"),token,null).andExpect(status().isUnauthorized());
  call(post("/api/v1/auth/reactivate"),null,Map.of("username",user.getIdentityCode(),"password",PASSWORD)).andExpect(status().isOk());assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
 }
 @Test void disabledByAdministratorCannotBeReactivated()throws Exception{db.update("UPDATE app_user SET status='DISABLED' WHERE id=?",user.getId());call(post("/api/v1/auth/reactivate"),null,Map.of("username",user.getUsername(),"password",PASSWORD)).andExpect(status().isBadRequest());assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.DISABLED);}
 @Test void activeAiRequestsBlockClosureAndDisabledOwnersCannotReceiveLateWrites()throws Exception{
  try(var permit=aiLimits.acquire(user.getId())){call(post("/api/v1/account/closure/prepare"),token,Map.of("mode","PURGE","currentPassword",PASSWORD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("AI_TASK_ACTIVE"));}
  var ticket=prepare("SUSPEND");advance();call(post("/api/v1/account/closure/confirm"),token,confirm(ticket)).andExpect(status().isOk());
  assertThatThrownBy(()->aiPersistence.recordUsage(new PersonalAiUsageEntity(user.getId(),AiProvider.OPENAI,"synthetic","draft","SUCCEEDED",(Long)null,null,null))).isInstanceOf(com.robustvision.platform.common.BusinessException.class);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM personal_ai_usage WHERE owner_id=?",Long.class,user.getId())).isZero();
 }
 @Test void lastAdministratorCannotCloseAndRegistrationRespectsPrivacy()throws Exception{
  var role=roles.findByCode("ADMIN").orElseGet(()->roles.save(new RoleEntity("ADMIN","Admin","Synthetic",Permissions.allCodes())));user.setRole(role);users.save(user);call(post("/api/v1/account/closure/prepare"),token,Map.of("mode","SUSPEND","currentPassword",PASSWORD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("LAST_ADMIN"));
  roles.findByCode("RESEARCHER").orElseGet(()->roles.save(new RoleEntity("RESEARCHER","Researcher","Synthetic",Set.of("note:read","note:write"))));String name="registered"+UUID.randomUUID().toString().substring(0,8);
  call(post("/api/v1/auth/register"),null,Map.of("username",name,"email",name+"@example.invalid","password",PASSWORD,"displayName","Same name","discoverable",false)).andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value("Same name")).andExpect(jsonPath("$.data.identityCode").exists());assertThat(users.findByUsername(name).orElseThrow().isDiscoverable()).isFalse();
  call(post("/api/v1/auth/register"),null,Map.of("username","PKB-"+"A".repeat(32),"email","reserved@example.invalid","password",PASSWORD)).andExpect(status().isBadRequest());
 }
 @Test void privatePurgeDeletesOnlyOwnerDataAndCannotBeRecovered()throws Exception{
  note(token,"Private one","Secret personal content");var keep=note(otherToken,"Private two","Other content");var ticket=prepare("PURGE");advance();call(post("/api/v1/account/closure/confirm"),token,confirm(ticket)).andExpect(status().isOk());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM note WHERE owner_id=?",Long.class,user.getId())).isZero();call(get("/api/v1/notes/"+keep.path("id").asText()),otherToken,null).andExpect(status().isOk());
  assertThat(users.findById(user.getId()).orElseThrow().getDisplayName()).isEqualTo("已注销用户");assertThat(db.queryForObject("SELECT COUNT(*) FROM account_cleanup WHERE owner_id=? AND kind='TRAINING'",Long.class,user.getId())).isEqualTo(1);
  call(post("/api/v1/auth/reactivate"),null,Map.of("username",user.getUsername(),"password",PASSWORD)).andExpect(status().isUnauthorized());
 }
 @Test void batchIsAtomicOnStaleRevisionAndForeignOwnership()throws Exception{
  var a=note(token,"a","One");var b=note(token,"b","Two");var foreign=note(otherToken,"c","Three");
  var first=Map.of("id",a.path("id").asText(),"revision",a.path("revision").asLong());
  call(post("/api/v1/notes/batch"),token,Map.of("action","MOVE","library","英语","notes",List.of(first,Map.of("id",b.path("id").asText(),"revision",99)))).andExpect(status().isConflict());
  call(post("/api/v1/notes/batch"),token,Map.of("action","ARCHIVE","notes",List.of(first,Map.of("id",foreign.path("id").asText(),"revision",0)))).andExpect(status().isNotFound());
  call(get("/api/v1/notes/"+a.path("id").asText()),token,null).andExpect(jsonPath("$.data.library").value("研究"));
  call(post("/api/v1/notes/batch"),token,Map.of("action","MOVE","library","英语","notes",List.of(first))).andExpect(status().isOk());
 }
 @Test void summarySearchStillFindsTextBeyondPreviewWithoutLoadingFullBodies()throws Exception{String body="x".repeat(50000)+"needle-at-end";var n=note(token,"Long note",body);call(get("/api/v1/notes").param("keyword","needle-at-end"),token,null).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(n.path("id").asText())).andExpect(jsonPath("$.data[0].revision").isNumber());}
 @Test void preferencesAndReviewRemindersStayPrivateAndRespectMute()throws Exception{
  var n=note(token,"Review me","Private lesson");String id=n.path("id").asText();call(put("/api/v1/notes/"+id+"/reminder"),token,Map.of("date",LocalDate.now(ZoneOffset.UTC).minusDays(1).toString(),"repeatDays",7)).andExpect(status().isOk());call(get("/api/v1/notes/reminders"),otherToken,null).andExpect(jsonPath("$.data").isEmpty());call(get("/api/v1/notifications"),token,null).andExpect(jsonPath("$.data[?(@.id=='note-reviews')]").isNotEmpty());
  var prefs=Map.of("groups",false,"contacts",false,"mail",false,"study",false,"weeklyTarget",80,"studyDays",List.of(1,3,5),"zoneId","Asia/Shanghai","revision",0);
  call(put("/api/v1/account/preferences"),token,prefs).andExpect(status().isOk());call(put("/api/v1/account/preferences"),token,prefs).andExpect(status().isConflict());call(get("/api/v1/notifications"),token,null).andExpect(jsonPath("$.data").isEmpty());call(get("/api/v1/account/preferences"),otherToken,null).andExpect(jsonPath("$.data.weeklyTarget").value(70));
  call(post("/api/v1/notes/"+id+"/reminder/complete"),token,null).andExpect(status().isOk());call(get("/api/v1/notes/reminders"),token,null).andExpect(jsonPath("$.data").isEmpty());
 }
 @Test void identityLoginAndActivityNeverExposeSecrets()throws Exception{String identitySession=login(user.getIdentityCode());call(post("/api/v1/auth/login"),null,Map.of("username",user.getIdentityCode(),"password","IncorrectPassword123!")).andExpect(status().isUnauthorized());var logs=data(call(get("/api/v1/account/activity"),identitySession,null).andExpect(status().isOk()).andReturn());assertThat(logs.toString()).doesNotContain(PASSWORD,user.getUsername(),identitySession);assertThat(logs.toString()).contains("SUCCESS","LOGIN_FAILED");}
 @Test void groupResourcesAndSearchRejectOutsidersAndFilterRecalledMessages()throws Exception{var g=data(call(post("/api/v1/workspaces"),token,Map.of("name","Research","color","#57788d")).andExpect(status().isOk()).andReturn());long id=g.path("id").asLong();call(get("/api/v1/workspaces/"+id+"/resources"),otherToken,null).andExpect(status().isForbidden());call(get("/api/v1/workspaces/"+id+"/search").param("q",""),otherToken,null).andExpect(status().isForbidden());call(get("/api/v1/workspaces/"+id+"/resources"),token,null).andExpect(status().isOk());call(get("/api/v1/workspaces/"+id+"/search").param("senderId",user.getId().toString()).param("after","2020-01-01T00:00:00Z"),token,null).andExpect(status().isOk());call(post("/api/v1/account/closure/prepare"),token,Map.of("mode","SUSPEND","currentPassword",PASSWORD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("GROUP_OWNER_ACTIVE"));}
}
