package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
        if(jdbc.queryForObject("select count(*) from vocabulary_book where id='vocab-ec-ky'",Integer.class)==0) {
            try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
                var context=org.mockito.Mockito.mock(org.flywaydb.core.api.migration.Context.class);
                org.mockito.Mockito.when(context.getConnection()).thenReturn(connection);
                new db.migration.V17__expanded_vocabulary_catalog().migrate(context);
                db.migration.V23__merge_vocabulary_progress.backfill(connection);
                new db.migration.V24__daily_context_vocabulary().migrate(context);
            }
        }
        clock.set("2026-10-02T12:00:00Z");
        String suffix=UUID.randomUUID().toString().substring(0,8);
        var role=roles.save(new RoleEntity("VOCAB_"+suffix,"Vocabulary","Synthetic",Set.of("vocabulary:use")));
        alice=users.save(new UserEntity("va"+suffix,"unused","Alice","va"+suffix+"@example.invalid",role));
        bob=users.save(new UserEntity("vb"+suffix,"unused","Bob","vb"+suffix+"@example.invalid",role));
    }
    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,UserEntity person) {return request.with(user(person.getUsername()).authorities(com.robustvision.platform.security.Permissions.effective(person).stream().map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toArray(org.springframework.security.core.GrantedAuthority[]::new)));}
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
        var dashboard=dash(alice);assertThat(dashboard.path("settings").path("zoneId").isNull()).isTrue();assertThat(dashboard.path("books").size()).isEqualTo(18);
        assertThat(jdbc.queryForObject("select count(*) from vocabulary_word where book_id like 'vocab-%'",Integer.class)).isEqualTo(43778);
        mvc.perform(json(post("/api/v1/vocabulary/next"),alice,Map.of("bookId","vocab-daily","mode","LEARN"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VOCAB_TIMEZONE_REQUIRED"));
        mvc.perform(json(put("/api/v1/vocabulary/settings"),alice,Map.of("zoneId","Mars/Nowhere","dailyGoal",10))).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/v1/vocabulary/settings"),alice,Map.of("zoneId","Asia/Shanghai","dailyGoal",0))).andExpect(status().isBadRequest());
    }
    @Test void expandedBooksRemainLearnableSearchableAndOwnerScoped() throws Exception {
        var catalog=dash(alice).path("books");
        JsonNode postgraduate=null;
        for(var book:catalog) if(book.path("id").asText().equals("vocab-ec-ky"))postgraduate=book;
        assertThat(postgraduate).isNotNull();assertThat(postgraduate.path("totalWords").asInt()).isEqualTo(4796);
        assertThat(postgraduate.path("learned").asInt()).isZero();
        settings(alice,"Asia/Shanghai",1,"vocab-ec-ky");
        var q=next(alice,"vocab-ec-ky","LEARN").path("question");
        assertThat(q.path("options").size()).isEqualTo(4);
        var result=answer(alice,q,true);assertThat(result.path("learningCorrect").asInt()).isEqualTo(1);
        for(var book:dash(bob).path("books"))assertThat(book.path("learning").asInt()).isZero();
        var later=call(as(get("/api/v1/vocabulary/books/vocab-ec-gre/words").param("page","210"),alice));
        assertThat(later.path("items").size()).isEqualTo(30);
        var search=call(as(get("/api/v1/vocabulary/books/vocab-ec-computing/words").param("query","algorithm"),alice));
        assertThat(search.path("items").get(0).path("term").asText()).isEqualTo("algorithm");
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
    private static final String EXPORT_PASSWORD="SyntheticExportPassword123!";
    private void exportPasswords() {
        String hash=new BCryptPasswordEncoder(4).encode(EXPORT_PASSWORD);
        jdbc.update("update app_user set password_hash=? where id in (?,?)",hash,alice.getId(),bob.getId());
    }
    private JsonNode export(UserEntity person) throws Exception {
        return call(json(post("/api/v1/account/export"),person,Map.of("currentPassword",EXPORT_PASSWORD,"ownerId",alice.getId())));
    }
    @Test void accountExportRoundTripsPrivateBookContentWithNewOwnershipAndExplicitRights() throws Exception {
        exportPasswords();
        ObjectNode input=mapper.valueToTree(importPayload());input.put("schemaVersion",1);
        input.put("title","  Synthetic portable book  ");input.put("description","  Original content for round-trip verification  ");
        input.put("attribution","  Original test author; permission limited to private study  ");
        ObjectNode first=(ObjectNode)input.path("words").get(0);
        first.put("ipa","/rɛd/");first.put("example","This is a \"red\" label \\ sample.");first.put("exampleTranslation","原创中文例句，保留标点🙂。");
        first.set("distractors",mapper.valueToTree(List.of("蓝色的","绿色的","黑色的","紫色的","黄色的","带\"引号\"的","带\\斜杠的","有形的🙂")));
        String ownId=call(json(post("/api/v1/vocabulary/books/import"),alice,input)).path("id").asText();
        settings(alice,"Asia/Shanghai",1,ownId);
        var answer=answer(alice,next(alice,ownId,"LEARN").path("question"),true);
        call(json(put("/api/v1/vocabulary/words/{id}/star",answer.path("wordId").asText()),alice,Map.of("starred",true)));
        JsonNode original=export(alice);assertThat(original.path("schemaVersion").asInt()).isEqualTo(5);
        assertThat(original.path("workspaceShortcuts").isArray()).isTrue();
        assertThat(original.path("learningRecords").isArray()).isTrue();
        assertThat(original.path("noteVersions").isArray()).isTrue();
        assertThat(original.path("vocabularyBookImports").size()).isEqualTo(1);
        ObjectNode portable=(ObjectNode)original.path("vocabularyBookImports").get(0);
        assertThat(portable.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(portable.path("rightsConfirmed").asBoolean()).isFalse();
        assertThat(portable.path("title").asText()).isEqualTo(input.path("title").asText().trim());
        assertThat(portable.path("description").asText()).isEqualTo(input.path("description").asText().trim());
        assertThat(portable.path("attribution").asText()).isEqualTo(input.path("attribution").asText().trim());
        assertThat(portable.path("words")).isEqualTo(input.path("words"));
        assertThat(mapper.readTree(original.path("vocabularyWords").get(0).path("distractors").asText())).isEqualTo(first.path("distractors"));
        assertThat(portable.toString()).doesNotContain(ownId,answer.path("wordId").asText(),"ownerId","progress","question","dueDate");
        assertThat(original.path("vocabularyProgress").size()).isEqualTo(1);

        // Knowing an owner/book ID, including with administrative authority, never grants export access.
        bob=users.findById(bob.getId()).orElseThrow();bob.setRole(roles.findByCode("ADMIN").orElseGet(()->roles.save(new RoleEntity("ADMIN","Admin","Synthetic",com.robustvision.platform.security.Permissions.allCodes()))));users.save(bob);
        JsonNode other=export(bob);
        assertThat(other.path("vocabularyBooks").isEmpty()).isTrue();assertThat(other.path("vocabularyWords").isEmpty()).isTrue();assertThat(other.path("vocabularyBookImports").isEmpty()).isTrue();
        mvc.perform(as(get("/api/v1/vocabulary/books/{id}/words",ownId),bob)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/account/export").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("currentPassword",EXPORT_PASSWORD,"ownerId",alice.getId())))).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/v1/account/export"),alice,Map.of("currentPassword","wrong"))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/vocabulary/books/import"),bob,portable)).andExpect(status().isBadRequest());
        assertThat(export(bob).path("vocabularyBookImports").isEmpty()).isTrue();

        ObjectNode confirmed=portable.deepCopy();confirmed.put("rightsConfirmed",true);confirmed.put("ownerId",alice.getId());confirmed.put("id",ownId);
        String newId=call(json(post("/api/v1/vocabulary/books/import"),bob,confirmed)).path("id").asText();
        assertThat(newId).isNotEqualTo(ownId);
        assertThat(jdbc.queryForObject("select owner_id from vocabulary_book where id=?",Long.class,newId)).isEqualTo(bob.getId());
        assertThat(jdbc.queryForList("select id from vocabulary_word where book_id=?",String.class,newId)).doesNotContainAnyElementsOf(jdbc.queryForList("select id from vocabulary_word where book_id=?",String.class,ownId));
        JsonNode reexported=export(bob);
        assertThat(reexported.path("vocabularyBookImports").get(0)).isEqualTo(portable);
        assertThat(reexported.path("vocabularyProgress").isEmpty()).isTrue();
        assertThat(export(alice).path("vocabularyBookImports")).isEqualTo(original.path("vocabularyBookImports"));
        mvc.perform(as(get("/api/v1/vocabulary/books/{id}/words",newId),alice)).andExpect(status().isNotFound());
    }
    @Test void portableBookVersionsAndImportBoundsAreValidatedWithoutPartialWrites() throws Exception {
        ObjectNode input=mapper.valueToTree(importPayload());
        for(int version:new int[]{0,-1,2,Integer.MAX_VALUE}) {
            input.put("schemaVersion",version);
            mvc.perform(json(post("/api/v1/vocabulary/books/import"),alice,input)).andExpect(status().isBadRequest());
        }
        input.put("schemaVersion",1);
        mvc.perform(as(post("/api/v1/vocabulary/books/import"),alice).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input)+" ".repeat(2*1024*1024))).andExpect(status().isPayloadTooLarge());
        ArrayNode words=(ArrayNode)input.path("words");ObjectNode template=((ObjectNode)words.get(0)).deepCopy();words.removeAll();
        for(int i=0;i<501;i++) {ObjectNode word=template.deepCopy();word.put("term","word"+(char)('a'+i/26)+(char)('a'+i%26));words.add(word);}
        mvc.perform(json(post("/api/v1/vocabulary/books/import"),alice,input)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from vocabulary_book where owner_id=?",Integer.class,alice.getId())).isZero();
        words.remove(500);exportPasswords();
        call(json(post("/api/v1/vocabulary/books/import"),alice,input));
        ObjectNode portable=(ObjectNode)export(alice).path("vocabularyBookImports").get(0);
        assertThat(portable.path("words").size()).isEqualTo(500);
        assertThat(mapper.writeValueAsBytes(portable).length).isLessThan(2*1024*1024);
        portable.put("rightsConfirmed",true);
        call(json(post("/api/v1/vocabulary/books/import"),bob,portable));
        assertThat(export(bob).path("vocabularyBookImports").get(0).path("words")).isEqualTo(input.path("words"));
        // A portable file does not bypass existing per-account capacity.
        for(int i=1;i<20;i++)jdbc.update("insert into vocabulary_book (id,owner_id,title,description,attribution,level,created_at) values (?,?,?,?,?,'自建词书',CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),alice.getId(),"Synthetic capacity "+i,"Original test data","Original test author");
        mvc.perform(json(post("/api/v1/vocabulary/books/import"),alice,portable)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("BOOK_LIMIT"));
        assertThat(jdbc.queryForObject("select count(*) from vocabulary_book where owner_id=?",Integer.class,alice.getId())).isEqualTo(20);
    }
    @Test void nearLimitLegacyBookRoundTripsWithDocumentedCompactExtraction() throws Exception {
        exportPasswords();
        ObjectNode input=mapper.valueToTree(importPayload());ArrayNode words=(ArrayNode)input.path("words");
        ObjectNode template=((ObjectNode)words.get(0)).deepCopy();template.put("example","原".repeat(400));template.put("exampleTranslation","译".repeat(400));
        template.set("distractors",mapper.valueToTree(List.of("错甲","错乙","错丙","错丁","错戊","错己","错庚","错辛")));words.removeAll();
        for(int i=0;i<500;i++){ObjectNode word=template.deepCopy();word.put("term","word"+(char)('a'+i/26)+(char)('a'+i%26));words.add(word);}
        int limit=2*1024*1024,target=limit-8,size=mapper.writeValueAsBytes(input).length;
        for(var word:words)for(int i=0;i<8;i++) {
            ArrayNode options=(ArrayNode)word.path("distractors");String old=options.get(i).asText();
            int available=target-size+old.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            String value="错".repeat(Math.min(159,Math.max(0,(available-1)/3)))+(char)('a'+i);
            int delta=value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length-old.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(delta>0){options.set(i,mapper.getNodeFactory().textNode(value));size+=delta;}
        }
        assertThat(mapper.writeValueAsBytes(input).length).isBetween(limit-10,limit-8);
        call(json(post("/api/v1/vocabulary/books/import"),alice,input));
        ObjectNode portable=(ObjectNode)export(alice).path("vocabularyBookImports").get(0);
        // Version/false-consent metadata can push an otherwise valid historical input over the cap.
        assertThat(mapper.writeValueAsBytes(portable).length).isGreaterThan(limit);
        mvc.perform(json(post("/api/v1/vocabulary/books/import"),bob,portable)).andExpect(status().isPayloadTooLarge());
        ObjectNode extracted=portable.deepCopy();assertThat(extracted.remove("schemaVersion").asInt()).isEqualTo(1);extracted.remove("rightsConfirmed");
        assertThat(mapper.writeValueAsBytes(extracted).length).isLessThan(limit);
        mvc.perform(json(post("/api/v1/vocabulary/books/import"),bob,extracted)).andExpect(status().isBadRequest());
        extracted.put("rightsConfirmed",true);
        assertThat(mapper.writeValueAsBytes(extracted).length).isLessThanOrEqualTo(limit);
        call(json(post("/api/v1/vocabulary/books/import"),bob,extracted));
        assertThat(export(bob).path("vocabularyBookImports").get(0)).isEqualTo(portable);
    }
    @Test void sameTermContinuesAcrossBooksDeduplicatesAndRemainsPrivate() throws Exception {
        settings(alice,"Etc/UTC",1,"vocab-daily");var q=next(alice,"vocab-daily","LEARN").path("question");var result=answer(alice,q,true);
        ObjectNode payload=mapper.valueToTree(importPayload());ArrayNode words=(ArrayNode)payload.path("words");
        ((ObjectNode)words.get(0)).put("term","  "+q.path("term").asText().toUpperCase(Locale.ROOT)+"  ");words.add(words.get(0).deepCopy());
        var imported=call(json(post("/api/v1/vocabulary/books/import"),alice,payload));assertThat(imported.path("duplicatesRemoved").asInt()).isEqualTo(1);assertThat(imported.path("totalWords").asInt()).isEqualTo(4);
        var entries=call(as(get("/api/v1/vocabulary/books/{id}/words",imported.path("id").asText()),alice)).path("items");
        var inherited=entries.get(0);assertThat(inherited.path("learningCorrect").asInt()).isEqualTo(1);
        call(json(put("/api/v1/vocabulary/words/{id}/skip",inherited.path("id").asText()),alice,Map.of("skipped",true)));
        var original=call(as(get("/api/v1/vocabulary/books/vocab-daily/words").param("filter","SKIPPED"),alice));assertThat(original.path("total").asInt()).isEqualTo(1);
        assertThat(original.path("items").get(0).path("learningCorrect").asInt()).isEqualTo(result.path("learningCorrect").asInt());
        mvc.perform(json(put("/api/v1/vocabulary/words/{id}/skip",inherited.path("id").asText()),bob,Map.of("skipped",false))).andExpect(status().isNotFound());
        assertThat(call(as(get("/api/v1/vocabulary/books/vocab-daily/words").param("filter","SKIPPED"),bob)).path("total").asInt()).isZero();
        call(json(put("/api/v1/vocabulary/words/{id}/skip",inherited.path("id").asText()),alice,Map.of("skipped",false)));
        assertThat(jdbc.queryForObject("select count(*) from vocabulary_progress where owner_id=?",Integer.class,alice.getId())).isEqualTo(1);
    }
    JsonNode recallQuestion() throws Exception {
        return call(json(post("/api/v1/vocabulary/next"),alice,Map.of("bookId","vocab-context","mode","LEARN","style","RECALL"))).path("question");
    }
    JsonNode recallAnswer(JsonNode q,String text) throws Exception {return call(json(post("/api/v1/vocabulary/questions/{id}/answer",q.path("id").asText()),alice,Map.of("text",text)));}
    @Test void sceneSpellingCollocationsAndHintsRecordDistinctEvidence() throws Exception {
        settings(alice,"Etc/UTC",1,"vocab-context");var q=recallQuestion();assertThat(q.path("practiceKind").asText()).isEqualTo("SPELLING");assertThat(q.path("options").isEmpty()).isTrue();
        var lesson=call(as(get("/api/v1/vocabulary/questions/{id}/lesson",q.path("id").asText()),alice));assertThat(lesson.path("ipa").asText()).isNotBlank();assertThat(lesson.path("collocations").size()).isEqualTo(4);
        var immediate=recallAnswer(q,"work");assertThat(immediate.path("evidence").asText()).isEqualTo("IMMEDIATE");assertThat(immediate.path("learningCorrect").asInt()).isZero();
        q=recallQuestion();assertThat(q.path("practiceKind").asText()).isEqualTo("COLLOCATION");clock.set("2026-10-02T12:02:00Z");
        var delayed=recallAnswer(q,"work on something");assertThat(delayed.path("correct").asBoolean()).isTrue();assertThat(delayed.path("evidence").asText()).isEqualTo("DELAYED");assertThat(delayed.path("independentCorrect").asInt()).isEqualTo(1);
        q=recallQuestion();call(json(post("/api/v1/vocabulary/questions/{id}/hint",q.path("id").asText()),alice,Map.of("level",3)));clock.set("2026-10-02T12:04:00Z");
        String expected=jdbc.queryForObject("select expected_text from vocabulary_question where id=?",String.class,q.path("id").asText());
        var prompted=recallAnswer(q,expected);assertThat(prompted.path("evidence").asText()).isEqualTo("PROMPTED");assertThat(prompted.path("learningCorrect").asInt()).isEqualTo(1);
        assertThat(recallAnswer(q,expected)).isEqualTo(prompted);
    }
    @Test void skipIsIdempotentAndDoesNotPretendToBeAnAnswer() throws Exception {
        settings(alice,"Etc/UTC",1,"vocab-context");var q=recallQuestion();String id=q.path("id").asText();
        var first=call(as(post("/api/v1/vocabulary/questions/{id}/skip",id),alice));assertThat(call(as(post("/api/v1/vocabulary/questions/{id}/skip",id),alice))).isEqualTo(first);
        assertThat(dash(alice).path("today").path("answers").asInt()).isZero();assertThat(first.path("learningCorrect").asInt()).isZero();
        mvc.perform(json(post("/api/v1/vocabulary/questions/{id}/answer",id),alice,Map.of("text","work"))).andExpect(status().isConflict());
        mvc.perform(as(post("/api/v1/vocabulary/questions/{id}/skip",id),bob)).andExpect(status().isNotFound());
        assertThat(recallQuestion().path("term").asText()).isNotEqualTo("work");
    }
    byte[] backup(UserEntity person) throws Exception {return mvc.perform(as(get("/api/v1/vocabulary/backup"),person)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();}
    JsonNode restore(UserEntity person,byte[] bytes) throws Exception {
        return call(as(multipart("/api/v1/vocabulary/backup/restore").file(new org.springframework.mock.web.MockMultipartFile("file","backup.json.gz","application/gzip",bytes)).param("confirmed","true"),person));
    }
    @Test void backupRestoreIsPortableOwnerScopedAndIdempotent() throws Exception {
        settings(alice,"Etc/UTC",1,"vocab-daily");var privateBook=call(json(post("/api/v1/vocabulary/books/import"),alice,importPayload()));var q=next(alice,"vocab-daily","LEARN").path("question");var answer=answer(alice,q,true);
        call(json(put("/api/v1/vocabulary/words/{id}/skip",answer.path("wordId").asText()),alice,Map.of("skipped",true)));
        byte[] file=backup(alice);assertThat(restore(bob,file).path("booksImported").asInt()).isEqualTo(1);assertThat(restore(bob,file).path("booksImported").asInt()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from vocabulary_book where owner_id=?",Integer.class,bob.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from vocabulary_progress where owner_id=?",Integer.class,bob.getId())).isEqualTo(1);
        assertThat(dash(bob).path("today").path("answers").asInt()).isEqualTo(1);
        assertThat(call(as(get("/api/v1/vocabulary/books/vocab-daily/words").param("filter","SKIPPED"),bob)).path("items").get(0).path("learningCorrect").asInt()).isEqualTo(1);
        mvc.perform(as(get("/api/v1/vocabulary/books/{id}/words",privateBook.path("id").asText()),bob)).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/vocabulary/backup/restore").file("file",file).param("confirmed","true")).andExpect(status().isUnauthorized());
        mvc.perform(as(multipart("/api/v1/vocabulary/backup/restore").file("file",file),alice)).andExpect(status().isBadRequest());
        mvc.perform(as(multipart("/api/v1/vocabulary/backup/restore").file("file",new byte[]{31,-117,0}).param("confirmed","true"),alice)).andExpect(status().isBadRequest());
    }
    @Test void invalidRestoreRollsBackNewBooksAndProtectsLaterLocalSkipChanges() throws Exception {
        settings(alice,"Etc/UTC",1,"vocab-daily");call(json(post("/api/v1/vocabulary/books/import"),alice,importPayload()));var q=next(alice,"vocab-daily","LEARN").path("question");var result=answer(alice,q,true);
        byte[] valid=backup(alice);
        ObjectNode invalid=(ObjectNode)mapper.readTree(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(valid)));((ObjectNode)invalid.path("progress").get(0)).put("term","nonexistentsyntheticheadword");
        mvc.perform(as(multipart("/api/v1/vocabulary/backup/restore").file("file",mapper.writeValueAsBytes(invalid)).param("confirmed","true"),bob)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from vocabulary_book where owner_id=?",Integer.class,bob.getId())).isZero();
        clock.set("2026-10-02T12:03:00Z");call(json(put("/api/v1/vocabulary/words/{id}/skip",result.path("wordId").asText()),alice,Map.of("skipped",true)));
        restore(alice,valid);assertThat(call(as(get("/api/v1/vocabulary/books/vocab-daily/words").param("filter","SKIPPED"),alice)).path("total").asInt()).isEqualTo(1);
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> value=new AtomicReference<>(Instant.parse("2026-10-02T12:00:00Z"));
        void set(String instant){value.set(Instant.parse(instant));}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return value.get();}
    }
    @TestConfiguration static class TestClock { @Bean MutableClock clock(){return new MutableClock();} }
}
