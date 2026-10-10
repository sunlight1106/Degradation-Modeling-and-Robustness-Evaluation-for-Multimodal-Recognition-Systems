package com.robustvision.platform.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
@org.springframework.boot.test.context.SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:vocabulary_skills;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","app.bootstrap.enabled=false","app.rate-limit.enabled=false"})
class VocabularySkillsIntegrationTest extends VocabularyIntegrationTest {
 @Test void listeningTracksSeparateWeakPointAndSharedCanonicalProgress()throws Exception{
  settings(alice,"UTC",10,"vocab-daily");
  var q=call(json(post("/api/v1/vocabulary/next"),alice,Map.of("bookId","vocab-daily","mode","LEARN","style","LISTENING"))).path("question");
  assertThat(q.path("practiceKind").asText()).isEqualTo("LISTENING");assertThat(q.path("prompt").asText()).doesNotContain(q.path("term").asText());
  call(json(post("/api/v1/vocabulary/questions/{id}/answer",q.path("id").asText()),alice,Map.of("text","synthetic-wrong")));
  var stats=call(as(get("/api/v1/vocabulary/statistics?days=90"),alice));assertThat(stats.path("days").size()).isEqualTo(90);assertThat(stats.path("weakPoints").get(0).path("kind").asText()).isEqualTo("LISTENING");
  assertThat(call(as(get("/api/v1/vocabulary/statistics?days=30"),bob)).path("weakPoints").size()).isZero();
 }
 @Test void repeatedSkillRestoreDoesNotAddCounts()throws Exception{
  settings(alice,"UTC",10,"vocab-daily");var q=call(json(post("/api/v1/vocabulary/next"),alice,Map.of("bookId","vocab-daily","mode","LEARN","style","LISTENING"))).path("question");
  call(json(post("/api/v1/vocabulary/questions/{id}/answer",q.path("id").asText()),alice,Map.of("text","synthetic-wrong")));
  byte[] archive=mvc.perform(as(get("/api/v1/vocabulary/backup"),alice)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
  for(int i=0;i<2;i++)mvc.perform(as(multipart("/api/v1/vocabulary/backup/restore").file(new org.springframework.mock.web.MockMultipartFile("file","synthetic.json.gz","application/gzip",archive)).param("confirmed","true"),alice)).andExpect(status().isOk());
  assertThat(jdbc.queryForObject("SELECT SUM(wrong_count) FROM vocabulary_skill WHERE owner_id=?",Integer.class,alice.getId())).isEqualTo(1);
 }
}
