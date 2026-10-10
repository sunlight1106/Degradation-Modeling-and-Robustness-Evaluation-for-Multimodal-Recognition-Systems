package com.robustvision.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ModerationIntegrationTest extends GroupCommunicationIntegrationTest {
 @Autowired JdbcTemplate db;
 @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;
 String message(String body)throws Exception{
  long group=group(ownerToken);membership(group,ownerToken,member,"MEMBER",Set.of("CONTENT_READ","CONTENT_WRITE","MEMBERS_READ")).andExpect(status().isOk());
  return data(postGroup(group,memberToken,body,null,false).andExpect(status().isOk()).andReturn()).path("id").asText();
 }
 String report(String type,String source,String token)throws Exception{
  return data(mvc.perform(json(post("/api/v1/moderation/reports"),token,Map.of("type",type,"sourceId",source,"reason","Synthetic report"))).andExpect(status().isOk()).andReturn()).path("id").asText();
 }
 void review(String id,String kind,Set<String> features)throws Exception{
  mvc.perform(json(post("/api/v1/moderation/reports/{id}/review",id),adminToken,Map.of("decision",kind,"minutes",60,"features",features,"reason","Synthetic verified reason"))).andExpect(status().isOk());
 }
 String penalty(String id)throws Exception{return data(mvc.perform(auth(get("/api/v1/moderation/reports/{id}",id),adminToken)).andExpect(status().isOk()).andReturn()).path("penalties").get(0).path("id").asText();}

 @Test void reportsRequireVisibleRealEvidenceAndReviewerPermission()throws Exception{
  String m=message("Original evidence"),id=report("MESSAGE",m,ownerToken);
  mvc.perform(json(post("/api/v1/moderation/reports"),outsiderToken,Map.of("type","MESSAGE","sourceId",m,"reason","Forged"))).andExpect(status().isForbidden());
  mvc.perform(json(post("/api/v1/moderation/reports"),memberToken,Map.of("type","MESSAGE","sourceId",m,"reason","Self report"))).andExpect(status().isBadRequest());
  mvc.perform(json(post("/api/v1/moderation/reports"),ownerToken,Map.of("type","MESSAGE","sourceId",m,"reason","Duplicate"))).andExpect(status().isConflict());
  mvc.perform(auth(get("/api/v1/moderation/reports"),ownerToken)).andExpect(status().isForbidden());
  mvc.perform(auth(get("/api/v1/moderation/reports/{id}",id),outsiderToken)).andExpect(status().isForbidden());
  mvc.perform(json(post("/api/v1/moderation/reports/{id}/review",id),ownerToken,Map.of("decision","BAN","minutes",60,"reason","Unauthorized"))).andExpect(status().isForbidden());
  db.update("UPDATE internal_message SET body='Message recalled' WHERE id=?",m);
  assertThat(data(mvc.perform(auth(get("/api/v1/moderation/reports/{id}",id),adminToken)).andReturn()).path("report").path("evidence").asText()).isEqualTo("Original evidence");
 }
 @Test void muteBlocksSendingButKeepsReadingAndExpiresWithoutChangingPermissions()throws Exception{
  String id=report("MESSAGE",message("Disputed text"),ownerToken);review(id,"MUTE",Set.of());String p=penalty(id);
  long group=group(ownerToken);membership(group,ownerToken,member,"MEMBER",Set.of("CONTENT_READ","CONTENT_WRITE","MEMBERS_READ")).andExpect(status().isOk());
  postGroup(group,memberToken,"Blocked",null,false).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("MODERATION_MUTE"));
  direct(memberToken,List.of(admin.getId()),null).andExpect(status().isForbidden());
  mvc.perform(auth(get("/api/v1/messages/groups/{id}",group),memberToken)).andExpect(status().isOk());
  db.update("UPDATE moderation_penalty SET expires_at=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),p);
  postGroup(group,memberToken,"Allowed after expiry",null,false).andExpect(status().isOk());
  assertThat(users.findById(member.getId()).orElseThrow().getPermissionOverrides()).isEmpty();
 }
 @Test void featureRestrictionsAreServerSideAndDoNotGrantRolePermissionsAtExpiry()throws Exception{
  String id=report("MESSAGE",message("Evidence"),ownerToken);review(id,"FEATURE",Set.of("GROUP","VOCABULARY"));
  mvc.perform(auth(get("/api/v1/workspaces"),memberToken)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("MODERATION_FEATURE"));
  mvc.perform(auth(get("/api/v1/moderation/mine"),memberToken)).andExpect(status().isOk());
  for(String path:List.of("/api/v1/auth/me","/api/v1/account/profile")) {
   var permissions=data(mvc.perform(auth(get(path),memberToken)).andExpect(status().isOk()).andReturn()).path("permissions");
   assertThat(permissions.toString()).doesNotContain("group:use","workspace:manage","vocabulary:use");
  }
  assertThat(data(mvc.perform(json(post("/api/v1/auth/login"),null,Map.of("username",member.getUsername(),"password",PASSWORD))).andExpect(status().isOk()).andReturn()).path("user").path("permissions").toString()).doesNotContain("group:use");
  mvc.perform(auth(get("/api/v1/vocabulary/books"),memberToken)).andExpect(status().isForbidden());
  db.update("UPDATE moderation_penalty SET expires_at=? WHERE report_id=?",Timestamp.from(Instant.now().minusSeconds(1)),id);
  mvc.perform(auth(get("/api/v1/workspaces"),memberToken)).andExpect(status().isOk());
  assertThat(data(mvc.perform(auth(get("/api/v1/auth/me"),memberToken)).andReturn()).path("permissions").toString()).contains("group:use").doesNotContain("vocabulary:use");
  mvc.perform(auth(get("/api/v1/vocabulary/books"),memberToken)).andExpect(status().isForbidden());
 }
 @Test void banAllowsOwnAppealOnlyAndRevocationDoesNotReactivateDisabledAccounts()throws Exception{
  String id=report("MESSAGE",message("Evidence"),ownerToken);review(id,"BAN",Set.of());String p=penalty(id);
  mvc.perform(auth(get("/api/v1/workspaces"),memberToken)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("MODERATION_BAN"));
  var mine=data(mvc.perform(auth(get("/api/v1/moderation/mine"),memberToken)).andExpect(status().isOk()).andReturn());
  assertThat(mine.path("penalties").get(0).has("reporter_id")).isFalse();
  assertThat(mine.path("penalties").get(0).path("expires_at").asText()).endsWith("Z");
  mvc.perform(json(post("/api/v1/moderation/penalties/{id}/appeal",p),outsiderToken,Map.of("reason","Other user's penalty"))).andExpect(status().isForbidden());
  mvc.perform(json(post("/api/v1/moderation/penalties/{id}/appeal",p),memberToken,Map.of("reason","Synthetic appeal"))).andExpect(status().isOk());
  assertThat(data(mvc.perform(auth(get("/api/v1/moderation/reports").param("status","APPEALS"),adminToken)).andExpect(status().isOk()).andReturn()).path("total").asLong()).isPositive();
  mvc.perform(json(post("/api/v1/moderation/penalties/{id}/decision",p),adminToken,Map.of("accept",true,"reason","Appeal verified"))).andExpect(status().isOk());
  mvc.perform(auth(get("/api/v1/workspaces"),memberToken)).andExpect(status().isOk());
  db.update("UPDATE app_user SET status='DISABLED' WHERE id=?",member.getId());
  mvc.perform(auth(get("/api/v1/moderation/mine"),memberToken)).andExpect(status().isUnauthorized());
  assertThat(db.queryForObject("SELECT status FROM app_user WHERE id=?",String.class,member.getId())).isEqualTo("DISABLED");
 }
 @Test void onlyEntireDirectThreatsTriggerShortAutomaticMuteAndDismissalReleasesIt()throws Exception{
  String plain=report("MESSAGE",message("引用：我要杀了你，不应这样说"),ownerToken);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM moderation_penalty WHERE report_id=?",Integer.class,plain)).isZero();
  String source=message("I will kill you!"),threat=report("MESSAGE",source,ownerToken),p=penalty(threat);
  var row=db.queryForMap("SELECT * FROM moderation_penalty WHERE id=?",p);assertThat(row.get("kind")).isEqualTo("MUTE");assertThat(row.get("automatic")).isEqualTo(true);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM moderation_penalty WHERE id=? AND expires_at<?",Integer.class,p,Timestamp.from(Instant.now().plusSeconds(1801)))).isEqualTo(1);
  review(threat,"DISMISS",Set.of());assertThat(db.queryForMap("SELECT * FROM moderation_penalty WHERE id=?",p).get("revoked_at")).isNotNull();
  String repeat=report("MESSAGE",source,adminToken);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM moderation_penalty WHERE report_id=?",Integer.class,repeat)).isZero();
 }
 @Test void reviewNeedsValidDurationReasonAndFeaturesAndCannotBeRepeated()throws Exception{
  String id=report("MESSAGE",message("Evidence"),ownerToken);
  for(var invalid:List.of(Map.of("decision","BAN","reason","Test","minutes",0),Map.of("decision","FEATURE","reason","Test","minutes",60,"features",List.of("ROOT")),Map.of("decision","MUTE","reason","","minutes",60)))
   mvc.perform(json(post("/api/v1/moderation/reports/{id}/review",id),adminToken,invalid)).andExpect(status().isBadRequest());
  review(id,"WARNING",Set.of());
  mvc.perform(json(post("/api/v1/moderation/reports/{id}/review",id),adminToken,Map.of("decision","BAN","reason","Second review","minutes",60))).andExpect(status().isConflict());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM moderation_event WHERE report_id=? AND action='REVIEW'",Integer.class,id)).isEqualTo(1);
 }
 @Test void directMailReportCannotBeForgedByNonParticipants()throws Exception{
  String m=data(direct(memberToken,List.of(admin.getId()),null).andExpect(status().isOk()).andReturn()).path("id").asText();
  String id=report("MESSAGE",m,adminToken);
  mvc.perform(json(post("/api/v1/moderation/reports"),ownerToken,Map.of("type","MESSAGE","sourceId",m,"reason","Not recipient"))).andExpect(status().isForbidden());
  review(id,"WARNING",Set.of());
 }
 @Test void mutedUsersCanLogoutAndUploadRestrictionsAlsoCoverMessageAttachments()throws Exception{
  String id=report("MESSAGE",message("Evidence"),ownerToken);review(id,"FEATURE",Set.of("UPLOAD"));
  long group=group(ownerToken);membership(group,ownerToken,member,"MEMBER",Set.of("CONTENT_READ","CONTENT_WRITE","MEMBERS_READ")).andExpect(status().isOk());
  postGroup(group,memberToken,"Attachment blocked",null,true).andExpect(status().isForbidden());
  postGroup(group,memberToken,"Plain text permitted",null,false).andExpect(status().isOk());
  mvc.perform(auth(post("/api/v1/account/logout"),memberToken)).andExpect(status().isOk());
 }
 @Test @org.springframework.transaction.annotation.Transactional
 void bannedAdminsDoNotCountAsAnAvailableHandoffForDisablingOrClosingTheLastAdmin()throws Exception{
  var second=user("check"+UUID.randomUUID().toString().substring(0,8),admin.getRole());String secondToken=login(second);
  db.update("UPDATE app_user SET access_expires_at=? WHERE role_id=? AND id NOT IN (?,?)",Timestamp.from(Instant.now().minusSeconds(60)),admin.getRole().getId(),admin.getId(),second.getId());
  long group=group(ownerToken);membership(group,ownerToken,second,"MEMBER",Set.of("CONTENT_READ","CONTENT_WRITE","MEMBERS_READ")).andExpect(status().isOk());
  String m=data(postGroup(group,secondToken,"Synthetic admin evidence",null,false).andExpect(status().isOk()).andReturn()).path("id").asText();
  em.flush();
  String id=report("MESSAGE",m,ownerToken);review(id,"BAN",Set.of());
  mvc.perform(json(patch("/api/v1/users/{id}",admin.getId()),adminToken,Map.of("status","DISABLED"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("SELF_STATUS_CHANGE"));
  mvc.perform(json(post("/api/v1/account/closure/prepare"),adminToken,Map.of("mode","SUSPEND","currentPassword",PASSWORD))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("LAST_ADMIN"));
  mvc.perform(json(post("/api/v1/moderation/reports/{id}/review",id),secondToken,Map.of("decision","DISMISS","reason","Cannot review own case"))).andExpect(status().isForbidden());
 }
 @Test void disabledNotesAreAlsoRemovedFromSearchSourcesAndGroupRestrictionsBlockGenericMessageDetails()throws Exception{
  member.setPermissionOverrides(Map.of("note:read",true,"note:write",true,"research:use",true));users.save(member);memberToken=login(member);
  var note=new com.robustvision.platform.domain.NoteEntity(member,"Synthetic source note","Private body","",com.robustvision.platform.domain.NoteStatus.DRAFT);
  var noteRepo=em.getEntityManagerFactory();
  // Use a short independent persistence transaction, exactly as normal note creation does.
  var writer=noteRepo.createEntityManager();try{writer.getTransaction().begin();writer.persist(note);writer.getTransaction().commit();}finally{writer.close();}
  mvc.perform(auth(get("/api/v1/research/sources/NOTE/{id}",note.getId()),memberToken)).andExpect(status().isOk());
  String m=message("Feature restriction evidence"),id=report("MESSAGE",m,ownerToken);review(id,"FEATURE",Set.of("NOTES","GROUP"));
  mvc.perform(auth(get("/api/v1/research/sources/NOTE/{id}",note.getId()),memberToken)).andExpect(status().isNotFound());
  var search=data(mvc.perform(auth(get("/api/v1/research/search").param("type","NOTE"),memberToken)).andExpect(status().isOk()).andReturn());assertThat(search.path("items").size()).isZero();
  mvc.perform(auth(get("/api/v1/messages/{id}",m),memberToken)).andExpect(status().isForbidden());
 }
 @Test void chatReportChecksParticipantsAndMuteBlocksChat()throws Exception{
  owner.setPermissionOverrides(Map.of("contacts:use",true));users.save(owner);ownerToken=login(owner);
  member.setPermissionOverrides(Map.of("contacts:use",true));users.save(member);memberToken=login(member);
  mvc.perform(json(post("/api/v1/social/contacts"),ownerToken,Map.of("userId",member.getId()))).andExpect(status().isOk());
  long contact=data(mvc.perform(auth(get("/api/v1/social/contacts"),memberToken)).andReturn()).get(0).path("id").asLong();
  mvc.perform(json(patch("/api/v1/social/contacts/{id}",contact),memberToken,Map.of("action","accept"))).andExpect(status().isOk());
  String m=data(mvc.perform(json(post("/api/v1/social/contacts/{id}/messages",contact),memberToken,Map.of("clientId",UUID.randomUUID().toString(),"body","Synthetic chat evidence"))).andExpect(status().isOk()).andReturn()).path("id").asText();
  mvc.perform(json(post("/api/v1/moderation/reports"),outsiderToken,Map.of("type","CHAT","sourceId",m,"reason","Foreign chat"))).andExpect(status().isForbidden());
  String id=report("CHAT",m,ownerToken);review(id,"MUTE",Set.of());
  mvc.perform(json(post("/api/v1/moderation/reports"),ownerToken,Map.of("type","CHAT","sourceId","000"+m,"reason","Alternate identifier"))).andExpect(status().isConflict());
  mvc.perform(json(post("/api/v1/social/contacts/{id}/messages",contact),memberToken,Map.of("clientId",UUID.randomUUID().toString(),"body","Blocked chat"))).andExpect(status().isForbidden());
 }
}
