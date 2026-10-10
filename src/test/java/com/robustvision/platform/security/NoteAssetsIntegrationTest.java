package com.robustvision.platform.security;
import org.junit.jupiter.api.Test;
import com.robustvision.platform.domain.*;
import org.springframework.mock.web.MockMultipartFile;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
class NoteAssetsIntegrationTest extends GroupCommunicationIntegrationTest {
 String note(String auth,String body)throws Exception{return data(mvc.perform(json(post("/api/v1/notes"),auth,Map.of("title","Synthetic note","body",body,"status","DRAFT"))).andExpect(status().isOk()).andReturn()).path("id").asText();}
 @Test void ownedBacklinksAndImagesNeverAuthorizeAnotherAccount()throws Exception{
  var role=roles.save(new RoleEntity("NOTE_"+UUID.randomUUID().toString().substring(0,8),"Notes","Synthetic",Set.of("note:read","note:write")));var first=user("note"+UUID.randomUUID().toString().substring(0,8),role);var second=user("note"+UUID.randomUUID().toString().substring(0,8),role);String a=login(first),b=login(second);
  String target=note(a,"Target"),source=note(a,"[Target](/app/notes/"+target+"/edit)");note(b,"[Unowned target](/app/notes/"+target+"/edit)");
  var back=data(mvc.perform(auth(get("/api/v1/notes/{id}/backlinks",target),a)).andExpect(status().isOk()).andReturn());assertThat(back.size()).isEqualTo(1);assertThat(back.get(0).path("id").asText()).isEqualTo(source);
  mvc.perform(auth(get("/api/v1/notes/{id}/backlinks",target),b)).andExpect(status().isNotFound());
  byte[] image=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg==");
  var asset=data(mvc.perform(auth(multipart("/api/v1/notes/{id}/attachments",target).file(new MockMultipartFile("file","synthetic.png","image/png",image)),a)).andExpect(status().isOk()).andReturn()).path("id").asText();
  mvc.perform(auth(get("/api/v1/notes/{id}/attachments/{asset}",target,asset),a)).andExpect(status().isOk());mvc.perform(auth(get("/api/v1/notes/{id}/attachments/{asset}",target,asset),b)).andExpect(status().isNotFound());
 }
}
