package com.robustvision.platform.security;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
class GroupLifecycleIntegrationTest extends GroupCommunicationIntegrationTest {
 @Test void globalMessageDenialStillAppliesToGroupOwners()throws Exception{
  var role=roles.save(new com.robustvision.platform.domain.RoleEntity("NOMSG_"+UUID.randomUUID().toString().substring(0,8),"No messaging","Synthetic",Set.of("workspace:manage","group:use")));var account=user("nomsg"+UUID.randomUUID().toString().substring(0,8),role);String auth=login(account);long id=group(auth);
  mvc.perform(auth(get("/api/v1/workspaces/{id}/reports",id),auth)).andExpect(status().isForbidden());
  mvc.perform(json(post("/api/v1/workspaces/{id}/messages/{message}/report",id,UUID.randomUUID().toString()),auth,Map.of("reason","Synthetic reason"))).andExpect(status().isForbidden());
 }
 @Test void recipientConsentArchiveAndDissolveControlAccess()throws Exception {
  long id=group(ownerToken);
  var invite=Map.of("targetId",member.getId(),"role","MEMBER");
  mvc.perform(json(post("/api/v1/workspaces/{id}/invitations",id),ownerToken,invite)).andExpect(status().isOk());
  mvc.perform(auth(get("/api/v1/workspaces/{id}/features",id),memberToken)).andExpect(status().isForbidden());
  var pending=data(mvc.perform(auth(get("/api/v1/workspaces/invitations"),memberToken)).andExpect(status().isOk()).andReturn());String ticket=pending.get(0).path("id").asText();
  mvc.perform(json(post("/api/v1/workspaces/invitations/{id}/decision",ticket),outsiderToken,Map.of("accept",true))).andExpect(status().isForbidden());
  mvc.perform(json(post("/api/v1/workspaces/invitations/{id}/decision",ticket),memberToken,Map.of("accept",true))).andExpect(status().isOk());
  postGroup(id,memberToken,"Synthetic post",null,false).andExpect(status().isOk());
  mvc.perform(json(put("/api/v1/workspaces/{id}/lifecycle",id),ownerToken,Map.of("archived",true,"acceptRequests",false))).andExpect(status().isOk());
  postGroup(id,memberToken,"Must not post",null,false).andExpect(status().isConflict());
  mvc.perform(auth(get("/api/v1/messages/groups/{id}",id),memberToken)).andExpect(status().isOk());
  var name=data(mvc.perform(auth(get("/api/v1/workspaces/{id}",id),ownerToken)).andReturn()).path("name").asText();
  mvc.perform(json(post("/api/v1/workspaces/{id}/dissolve",id),ownerToken,Map.of("name",name,"password",PASSWORD))).andExpect(status().isOk());
  mvc.perform(auth(get("/api/v1/messages/groups/{id}",id),memberToken)).andExpect(status().isForbidden());
 }
 @Test void recallRemovesAttachmentAccessAndReportsStayWithinGroup()throws Exception {
  long id=group(ownerToken),other=group(ownerToken);membership(other,ownerToken,outsider,"MEMBER",Set.of("CONTENT_READ","CONTENT_WRITE","MEMBERS_READ")).andExpect(status().isOk());membership(id,ownerToken,member,"MEMBER",Set.of("CONTENT_READ","CONTENT_WRITE","MEMBERS_READ")).andExpect(status().isOk());
  var post=data(postGroup(id,ownerToken,"Synthetic evidence",null,true).andExpect(status().isOk()).andReturn());String message=post.path("id").asText(),url=post.path("attachments").get(0).path("downloadUrl").asText();
  mvc.perform(json(post("/api/v1/workspaces/{id}/messages/{message}/report",other,message),outsiderToken,Map.of("reason","Synthetic reason"))).andExpect(status().isBadRequest());
  mvc.perform(json(post("/api/v1/workspaces/{id}/messages/{message}/report",id,message),memberToken,Map.of("reason","Synthetic reason"))).andExpect(status().isOk());
  mvc.perform(auth(get("/api/v1/workspaces/{id}/reports",id),memberToken)).andExpect(status().isForbidden());
  mvc.perform(json(post("/api/v1/workspaces/{id}/messages/{message}/recall",id,message),memberToken,Map.of())).andExpect(status().isForbidden());
  mvc.perform(json(post("/api/v1/workspaces/{id}/messages/{message}/recall",id,message),ownerToken,Map.of())).andExpect(status().isOk());
  mvc.perform(auth(get(url),memberToken)).andExpect(status().isNotFound());
  var history=data(mvc.perform(auth(get("/api/v1/messages/groups/{id}",id),memberToken)).andExpect(status().isOk()).andReturn());assertThat(history.get(0).path("body").asText()).isEqualTo("消息已撤回");assertThat(history.get(0).path("attachments").size()).isZero();
 }
}
