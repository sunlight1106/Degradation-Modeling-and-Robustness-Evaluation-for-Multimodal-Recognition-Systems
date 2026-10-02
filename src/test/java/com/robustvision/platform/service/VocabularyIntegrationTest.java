package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.domain.RoleEntity;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.repository.RoleRepository;
import com.robustvision.platform.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:vocabulary;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","app.bootstrap.enabled=false","app.rate-limit.enabled=false"})
@AutoConfigureMockMvc
@Import(VocabularyIntegrationTest.TestClock.class)
@Sql(scripts="file:database/migrations/V11__original_starter_vocabulary.sql",executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class VocabularyIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired MutableClock clock;
    UserEntity alice,bob;
    @BeforeEach void fixtures() throws Exception {
        clock.set("2026-10-02T12:00:00Z");
        String suffix=UUID.randomUUID().toString().substring(0,8);
        var role=roles.save(new RoleEntity("VOCAB_"+suffix,"Vocabulary","Synthetic",Set.of()));
        alice=users.save(new UserEntity("va"+suffix,"unused","Alice","va"+suffix+"@example.invalid",role));
        bob=users.save(new UserEntity("vb"+suffix,"unused","Bob","vb"+suffix+"@example.invalid",role));
    }
    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,UserEntity person) {return request.with(user(person.getUsername()));}
    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,UserEntity person,Object value) throws Exception {return as(request,person).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(value));}
    JsonNode data(byte[] bytes) throws Exception{return mapper.readTree(bytes).path("data");}
    JsonNode call(MockHttpServletRequestBuilder request) throws Exception{return data(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());}
    JsonNode dash(UserEntity person) throws Exception{return call(as(get("/api/v1/vocabulary/dashboard"),person));}
    void settings(UserEntity person,String zone,int goal,String book) throws Exception{call(json(put("/api/v1/vocabulary/settings"),person,Map.of("zoneId",zone,"dailyGoal",goal,"selectedBookId",book)));}
    JsonNode next(UserEntity person,String book,String mode) throws Exception{return call(json(post("/api/v1/vocabulary/next"),person,Map.of("bookId",book,"mode",mode)));}
    JsonNode answer(UserEntity person,JsonNode question,boolean correct) throws Exception {
        String right=jdbc.queryForObject("select correct_option_id from vocabulary_question where id=?",String.class,question.path("id").asText());
        String option=right;
        if(!correct) for(var o:question.path("options"))if(!o.path("id").asText().equals(right)){option=o.path("id").asText();break;}
        return call(json(post("/api/v1/vocabulary/questions/{id}/answer",question.path("id").asText()),person,Map.of("optionId",option,"correct",true,"ownerId",bob.getId())));
    }
    JsonNode master(String zone) throws Exception {
        settings(alice,zone,1,"vocab-daily");JsonNode result=null;
        for(int i=1;i<=4;i++){var q=next(alice,"vocab-daily","LEARN").path("question");result=answer(alice,q,true);assertThat(result.path("learningCorrect").asInt()).isEqualTo(i);}
        return result;
    }
    Map<String,Object> importPayload() {
        List<Map<String,Object>> words=new ArrayList<>();
        String[] terms={"red","blue","green","black"};String[] meanings={"红色的","蓝色的","绿色的","黑色的"};
        for(int i=0;i<4;i++){var ds=new ArrayList<>(Arrays.asList(meanings));ds.remove(i);words.add(Map.of("term",terms[i],"ipa","/test/","pos","adj.","meaning",meanings[i],"example","This is "+terms[i]+".","exampleTranslation","原创示例","distractors",ds));}
        var data=new HashMap<String,Object>();data.put("title","Synthetic private book");data.put("description","Original test vocabulary");data.put("attribution","Original test data");data.put("rightsConfirmed",true);data.put("words",words);return data;
    }
    @Test void requiresAuthenticationAndExplicitValidTimezone() throws Exception {
        mvc.perform(get("/api/v1/vocabulary/dashboard")).andExpect(status().isUnauthorized());
        var dashboard=dash(alice);assertThat(dashboard.path("settings").path("zoneId").isNull()).isTrue();assertThat(dashboard.path("books").size()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from vocabulary_word where book_id like 'vocab-%'",Integer.class)).isEqualTo(60);
        mvc.perform(json(post("/api/v1/vocabulary/next"),alice,Map.of("bookId","vocab-daily","mode","LEARN"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VOCAB_TIMEZONE_REQUIRED"));
        mvc.perform(json(put("/api/v1/vocabulary/settings"),alice,Map.of("zoneId","Mars/Nowhere","dailyGoal",10))).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/v1/vocabulary/settings"),alice,Map.of("zoneId","Asia/Shanghai","dailyGoal",0))).andExpect(status().isBadRequest());
    }
    @Test void questionsDoNotLeakAnswerAndInvalidOptionsDoNotMutate() throws Exception {
        settings(alice,"Asia/Shanghai",2,"vocab-daily");var q=next(alice,"vocab-daily","LEARN").path("question");
        assertThat(q.has("correctOptionId")).isFalse();assertThat(q.has("wordId")).isFalse();assertThat(q.has("meaning")).isFalse();assertThat(q.has("example")).isFalse();assertThat(q.path("ipa").asText()).isNotBlank();assertThat(q.path("pos").asText()).isNotBlank();
        assertThat(q.path("options").size()).isEqualTo(4);Set<String> values=new HashSet<>();for(var o:q.path("options"))values.add(o.path("meaning").asText());assertThat(values).hasSize(4);
        mvc.perform(json(post("/api/v1/vocabulary/questions/{id}/answer",q.path("id").asText()),alice,Map.of("optionId","forged"))).andExpect(status().isBadRequest());
        assertThat(dash(alice).path("today").path("answers").asInt()).isZero();
        assertThat(next(alice,"vocab-daily","LEARN").path("question").path("id").asText()).isEqualTo(q.path("id").asText());
    }
    @Test void fourCorrectTransitionsOnceAndGoalStopsNewWords() throws Exception {
        var result=master("Asia/Shanghai");assertThat(result.path("newlyLearned").asBoolean()).isTrue();assertThat(result.path("dueDate").asText()).isEqualTo("2026-10-03");
        var dashboard=dash(alice);assertThat(dashboard.path("today").path("learned").asInt()).isEqualTo(1);assertThat(dashboard.path("today").path("correct").asInt()).isEqualTo(4);
        assertThat(next(alice,"vocab-daily","LEARN").path("question").isNull()).isTrue();assertThat(next(alice,"vocab-daily","REVIEW").path("question").isNull()).isTrue();
        assertThat(dash(bob).path("today").path("learned").asInt()).isZero();
    }
    @Test void wrongAnswersAddMistakeWithoutCreditAndPracticeDoesNotAdvanceSchedule() throws Exception {
        settings(alice,"Asia/Shanghai",1,"vocab-daily");var q=next(alice,"vocab-daily","LEARN").path("question");var wrong=answer(alice,q,false);
        assertThat(wrong.path("learningCorrect").asInt()).isZero();assertThat(wrong.path("dueDate").isNull()).isTrue();
        var practice=next(alice,"vocab-daily","MISTAKES").path("question");var fixed=answer(alice,practice,true);
        assertThat(fixed.path("learningCorrect").asInt()).isZero();assertThat(fixed.path("dueDate").isNull()).isTrue();assertThat(next(alice,"vocab-daily","MISTAKES").path("question").isNull()).isTrue();
        var page=call(as(get("/api/v1/vocabulary/books/vocab-daily/words?filter=ALL"),alice));assertThat(page.path("items").get(0).path("wrongCount").asInt()).isEqualTo(1);
    }
    @Test void retriesAndConcurrentDuplicateAnswersOnlyGrantOneCredit() throws Exception {
        settings(alice,"Asia/Shanghai",1,"vocab-daily");var q=next(alice,"vocab-daily","LEARN").path("question");
        ExecutorService pool=Executors.newFixedThreadPool(6);
        try {List<Future<JsonNode>> tasks=new ArrayList<>();for(int i=0;i<6;i++)tasks.add(pool.submit(()->answer(alice,q,true)));for(var task:tasks)assertThat(task.get(20,TimeUnit.SECONDS).path("learningCorrect").asInt()).isEqualTo(1);} finally {pool.shutdownNow();}
        assertThat(dash(alice).path("today").path("answers").asInt()).isEqualTo(1);assertThat(answer(alice,q,false).path("correct").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select learning_correct from vocabulary_progress where owner_id=?",Integer.class,alice.getId())).isEqualTo(1);
    }
    @Test void concurrentNextRequestsResumeExactlyOneQuestion() throws Exception {
        settings(alice,"Asia/Shanghai",1,"vocab-daily");
        ExecutorService pool=Executors.newFixedThreadPool(6);
        try {
            List<Future<JsonNode>> tasks=new ArrayList<>();
            for(int i=0;i<6;i++)tasks.add(pool.submit(()->next(alice,"vocab-daily","LEARN")));
            Set<String> ids=new HashSet<>();for(var task:tasks)ids.add(task.get(20,TimeUnit.SECONDS).path("question").path("id").asText());
            assertThat(ids).hasSize(1);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("select count(*) from vocabulary_question where owner_id=?",Integer.class,alice.getId())).isEqualTo(1);
    }
    @Test void roundRobinBatchRotatesBeforeRepeatingAndExpiredOrReplacedQuestionFails() throws Exception {
        settings(alice,"Asia/Shanghai",3,"vocab-daily");Set<String> terms=new HashSet<>();for(int i=0;i<3;i++){var q=next(alice,"vocab-daily","LEARN").path("question");terms.add(q.path("term").asText());answer(alice,q,true);clock.set(clock.instant().plusSeconds(1).toString());}assertThat(terms).hasSize(3);
        var q=next(alice,"vocab-daily","LEARN").path("question");next(alice,"vocab-travel","LEARN");
        String correct=jdbc.queryForObject("select correct_option_id from vocabulary_question where id=?",String.class,q.path("id").asText());
        mvc.perform(json(post("/api/v1/vocabulary/questions/{id}/answer",q.path("id").asText()),alice,Map.of("optionId",correct))).andExpect(status().isConflict());
        var fresh=next(alice,"vocab-daily","LEARN").path("question");clock.set(clock.instant().plusSeconds(1801).toString());
        mvc.perform(json(post("/api/v1/vocabulary/questions/{id}/answer",fresh.path("id").asText()),alice,Map.of("optionId",fresh.path("options").get(0).path("id").asText()))).andExpect(status().isConflict());
    }
    @Test void nextLocalDayAndSpringDstUseCalendarDateRatherThanUtcOrTwentyFourHours() throws Exception {
        clock.set("2026-03-08T04:30:00Z");var result=master("America/New_York");assertThat(result.path("dueDate").asText()).isEqualTo("2026-03-08");
        clock.set("2026-03-08T04:59:00Z");assertThat(next(alice,"vocab-daily","REVIEW").path("question").isNull()).isTrue();
        clock.set("2026-03-08T05:01:00Z");var q=next(alice,"vocab-daily","REVIEW").path("question");assertThat(q.isNull()).isFalse();var reviewed=answer(alice,q,true);assertThat(reviewed.path("dueDate").asText()).isEqualTo("2026-03-11");
        assertThat(dash(alice).path("streak").asInt()).isEqualTo(2);
    }
    @Test void fallDstWrongReviewReturnsToTomorrowAndCannotReviewTwiceToday() throws Exception {
        clock.set("2026-11-01T03:50:00Z");assertThat(master("America/New_York").path("dueDate").asText()).isEqualTo("2026-11-01");
        clock.set("2026-11-01T04:01:00Z");var wrong=answer(alice,next(alice,"vocab-daily","REVIEW").path("question"),false);assertThat(wrong.path("dueDate").asText()).isEqualTo("2026-11-02");assertThat(wrong.path("reviewStage").asInt()).isZero();
        clock.set("2026-11-01T06:30:00Z");assertThat(next(alice,"vocab-daily","REVIEW").path("question").isNull()).isTrue();
        var practice=answer(alice,next(alice,"vocab-daily","MISTAKES").path("question"),true);assertThat(practice.path("dueDate").asText()).isEqualTo("2026-11-02");
        clock.set("2026-11-02T05:01:00Z");var recovered=answer(alice,next(alice,"vocab-daily","REVIEW").path("question"),true);assertThat(recovered.path("dueDate").asText()).isEqualTo("2026-11-05");
    }
    @Test void changingTimezoneExpiresPendingQuestionAndPreservesStoredHistoryDates() throws Exception {
        settings(alice,"Asia/Shanghai",2,"vocab-daily");
        answer(alice,next(alice,"vocab-daily","LEARN").path("question"),true);
        var pending=next(alice,"vocab-daily","LEARN").path("question");
        settings(alice,"America/Los_Angeles",2,"vocab-daily");
        mvc.perform(json(post("/api/v1/vocabulary/questions/{id}/answer",pending.path("id").asText()),alice,
                Map.of("optionId",pending.path("options").get(0).path("id").asText()))).andExpect(status().isConflict());
        assertThat(next(alice,"vocab-daily","LEARN").path("question").path("id").asText()).isNotEqualTo(pending.path("id").asText());
        assertThat(jdbc.queryForObject("select study_date from vocabulary_question where owner_id=? and answered_at is not null",java.sql.Date.class,alice.getId()).toLocalDate()).isEqualTo(LocalDate.parse("2026-10-02"));
    }
    @Test void successfulReviewsExpandIntervalsAndCapAtSixtyDays() throws Exception {
        master("Etc/UTC");LocalDate due=LocalDate.parse("2026-10-03");
        for(int interval:new int[]{3,7,14,30,60,60}) {
            clock.set(due.atTime(12,0).toInstant(ZoneOffset.UTC).toString());
            var reviewed=answer(alice,next(alice,"vocab-daily","REVIEW").path("question"),true);
            due=due.plusDays(interval);assertThat(reviewed.path("dueDate").asText()).isEqualTo(due.toString());
            assertThat(next(alice,"vocab-daily","REVIEW").path("question").isNull()).isTrue();
        }
    }
    @Test void booksQuestionsStarsAndImportsAreOwnerIsolated() throws Exception {
        var own=call(json(post("/api/v1/vocabulary/books/import"),alice,importPayload()));String id=own.path("id").asText();settings(alice,"Asia/Shanghai",1,id);settings(bob,"Asia/Shanghai",1,"vocab-daily");
        assertThat(dash(bob).path("books").toString()).doesNotContain(id);mvc.perform(as(get("/api/v1/vocabulary/books/{id}/words",id),bob)).andExpect(status().isNotFound());mvc.perform(json(post("/api/v1/vocabulary/next"),bob,Map.of("bookId",id,"mode","LEARN"))).andExpect(status().isNotFound());
        var q=next(alice,id,"LEARN").path("question");mvc.perform(json(post("/api/v1/vocabulary/questions/{id}/answer",q.path("id").asText()),bob,Map.of("optionId",q.path("options").get(0).path("id").asText()))).andExpect(status().isNotFound());
        String word=jdbc.queryForObject("select word_id from vocabulary_question where id=?",String.class,q.path("id").asText());mvc.perform(json(put("/api/v1/vocabulary/words/{id}/star",word),bob,Map.of("starred",true))).andExpect(status().isNotFound());
        call(json(put("/api/v1/vocabulary/words/{id}/star",word),alice,Map.of("starred",true)));assertThat(dash(alice).path("starredTotal").asInt()).isEqualTo(1);assertThat(dash(bob).path("starredTotal").asInt()).isZero();
    }
    @SuppressWarnings("unchecked")
    @Test void importValidatesRightsSizeFieldsDuplicateTermsAndAmbiguousOptionsAtomically() throws Exception {
        var payload=importPayload();payload.put("rightsConfirmed",false);mvc.perform(json(post("/api/v1/vocabulary/books/import"),alice,payload)).andExpect(status().isBadRequest());
        payload=importPayload();payload.put("words",List.of());mvc.perform(json(post("/api/v1/vocabulary/books/import"),alice,payload)).andExpect(status().isBadRequest());
        payload=importPayload();var words=new ArrayList<>((List<Map<String,Object>>)payload.get("words"));var changed=new HashMap<>(words.get(1));changed.put("term"," RED ");words.set(1,changed);payload.put("words",words);mvc.perform(json(post("/api/v1/vocabulary/books/import"),alice,payload)).andExpect(status().isBadRequest());
        payload=importPayload();words=new ArrayList<>((List<Map<String,Object>>)payload.get("words"));changed=new HashMap<>(words.get(0));changed.put("distractors",List.of("红色的","蓝色的","绿色的"));words.set(0,changed);payload.put("words",words);mvc.perform(json(post("/api/v1/vocabulary/books/import"),alice,payload)).andExpect(status().isBadRequest());
        payload=importPayload();words=new ArrayList<>((List<Map<String,Object>>)payload.get("words"));changed=new HashMap<>(words.get(0));changed.put("ipa","");words.set(0,changed);payload.put("words",words);mvc.perform(json(post("/api/v1/vocabulary/books/import"),alice,payload)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from vocabulary_book where owner_id=?",Integer.class,alice.getId())).isZero();
    }
    @SuppressWarnings("unchecked")
    @Test void nullWordsEscapedOversizedOptionsAndControlCharactersAreRejectedWithoutPartialWrites() throws Exception {
        var payload=importPayload();var words=new ArrayList<>((List<Map<String,Object>>)payload.get("words"));words.set(0,null);payload.put("words",words);
        mvc.perform(json(post("/api/v1/vocabulary/books/import"),alice,payload)).andExpect(status().isBadRequest());
        payload=importPayload();words=new ArrayList<>((List<Map<String,Object>>)payload.get("words"));var changed=new HashMap<>(words.get(0));
        List<String> escaped=new ArrayList<>();for(int i=0;i<8;i++)escaped.add("\\".repeat(159)+i);changed.put("distractors",escaped);words.set(0,changed);payload.put("words",words);
        mvc.perform(json(post("/api/v1/vocabulary/books/import"),alice,payload)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("OPTIONS_TOO_LONG"));
        payload=importPayload();words=new ArrayList<>((List<Map<String,Object>>)payload.get("words"));changed=new HashMap<>(words.get(0));changed.put("example","Control"+(char)0+"character");words.set(0,changed);payload.put("words",words);
        mvc.perform(json(post("/api/v1/vocabulary/books/import"),alice,payload)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_TEXT"));
        assertThat(jdbc.queryForObject("select count(*) from vocabulary_book where owner_id=?",Integer.class,alice.getId())).isZero();
    }
    @Test void browseSearchAndPaginationRemainBoundedAndNoOtherProgressAppears() throws Exception {
        settings(alice,"Asia/Shanghai",1,"vocab-daily");answer(alice,next(alice,"vocab-daily","LEARN").path("question"),true);
        var all=call(as(get("/api/v1/vocabulary/books/vocab-daily/words?filter=ALL&query=apple"),bob));assertThat(all.path("items").size()).isEqualTo(1);assertThat(all.path("items").get(0).path("learningCorrect").asInt()).isZero();
        mvc.perform(as(get("/api/v1/vocabulary/books/vocab-daily/words?filter=FORGED"),alice)).andExpect(status().isBadRequest());mvc.perform(as(get("/api/v1/vocabulary/books/vocab-daily/words?page=-1"),alice)).andExpect(status().isBadRequest());
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> value=new AtomicReference<>(Instant.parse("2026-10-02T12:00:00Z"));
        void set(String instant){value.set(Instant.parse(instant));}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value.get();}
    }
    @TestConfiguration static class TestClock { @Bean MutableClock clock(){return new MutableClock();} }
}
