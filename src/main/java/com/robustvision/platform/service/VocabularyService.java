package com.robustvision.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.VocabularyDtos.*;
import com.robustvision.platform.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Every mutation serializes on the authenticated user's database row, across application instances.
 * READ_COMMITTED is essential on MySQL: resolving the principal performs an initial read before
 * waiting for the owner lock. REPEATABLE_READ would retain that stale snapshot after acquiring
 * the lock and could miss a concurrent committed answer/progress row. */
@Service
public class VocabularyService {
    static final int MASTERY_TARGET = 4;
    private static final int[] REVIEW_DAYS = {3, 7, 14, 30, 60};
    private final EntityManager em;
    private final CurrentUserService current;
    private final UserRepository users;
    private final ObjectMapper json;
    private final Clock clock;
    private final VocabularyLessons lessons;
    public VocabularyService(EntityManager em, CurrentUserService current, UserRepository users,
                             ObjectMapper json, ObjectProvider<Clock> clock, VocabularyLessons lessons) {
        this.em=em; this.current=current; this.users=users; this.json=json;
        this.clock=clock.getIfAvailable(Clock::systemUTC); this.lessons=lessons;
    }
    long owner() { return current.requireCurrent().getId(); }
    long lockedOwner() {
        long id=owner(); users.findLockedById(id).orElseThrow(this::missing); return id;
    }
    Instant now() { return clock.instant(); }
    VocabularyProfileEntity profile(long owner) {
        var profile=em.find(VocabularyProfileEntity.class, owner);
        if(profile==null) {
            profile=new VocabularyProfileEntity(); profile.ownerId=owner; profile.updatedAt=now();
            em.persist(profile);
        }
        return profile;
    }
    Settings settings(VocabularyProfileEntity p) { return new Settings(p.zoneId,p.dailyGoal,p.selectedBookId,MASTERY_TARGET); }
    LocalDate today(VocabularyProfileEntity p) {
        if(p.zoneId==null) throw bad("VOCAB_TIMEZONE_REQUIRED","请先确认学习时区和每日目标");
        return now().atZone(ZoneId.of(p.zoneId)).toLocalDate();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Settings updateSettings(SettingsRequest request) {
        long owner=lockedOwner();
        if(!ZoneId.getAvailableZoneIds().contains(request.zoneId())) throw bad("INVALID_TIMEZONE","请选择有效的 IANA 时区，例如 Asia/Shanghai");
        if(request.dailyGoal()<1 || request.dailyGoal()>100) throw bad("INVALID_GOAL","每日目标须为 1 至 100 词");
        if(request.selectedBookId()!=null) book(request.selectedBookId(),owner);
        var p=profile(owner);
        if(p.zoneId!=null && !p.zoneId.equals(request.zoneId())) {
            em.createQuery("update VocabularyQuestionEntity q set q.expiresAt=:now where q.ownerId=:owner and q.answeredAt is null and q.expiresAt>:now")
                    .setParameter("owner",owner).setParameter("now",now()).executeUpdate();
        }
        p.zoneId=request.zoneId(); p.dailyGoal=request.dailyGoal();
        p.selectedBookId=request.selectedBookId(); p.updatedAt=now(); return settings(p);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Dashboard dashboard() {
        long owner=lockedOwner(); var p=profile(owner);
        LocalDate day=p.zoneId==null?null:today(p);
        var progress=progress(owner);
        // Aggregate the catalog in SQL: never load every definition once per book on each answer.
        var catalogStats=em.createQuery("select w.bookId, count(w), sum(case when p.learningCorrect>=4 then 1 else 0 end), sum(case when p.learningCorrect>0 and p.learningCorrect<4 then 1 else 0 end), sum(case when p.learningCorrect>=4 and p.skipped=false and p.dueDate<=:day then 1 else 0 end), sum(case when p.mistake=true and p.skipped=false then 1 else 0 end), sum(case when p.skipped=true then 1 else 0 end), sum(case when p.wordId<>w.id and p.learningCorrect>0 then 1 else 0 end) from VocabularyWordEntity w join VocabularyBookEntity b on b.id=w.bookId left join VocabularyProgressEntity p on p.termKey=w.termKey and p.ownerId=:owner where b.ownerId is null or b.ownerId=:owner group by w.bookId",Object[].class)
                .setParameter("owner",owner).setParameter("day",day==null?LocalDate.of(1000,1,1):day).getResultList().stream().collect(Collectors.toMap(row->(String)row[0],Function.identity()));
        var books=accessibleBooks(owner).stream().map(b->{var s=catalogStats.getOrDefault(b.id,new Object[]{b.id,0L,0L,0L,0L,0L,0L,0L});return new Book(b.id,b.title,b.description,b.attribution,b.level,b.ownerId!=null && b.ownerId==owner,((Number)s[1]).longValue(),((Number)s[2]).longValue(),((Number)s[3]).longValue(),((Number)s[4]).longValue(),((Number)s[5]).longValue(),((Number)s[6]).longValue(),((Number)s[7]).longValue(),0);}).toList();
        // Fixed local study dates are retained when a user changes their timezone later.
        var totals=em.createQuery("select q.studyDate, count(q), sum(case when q.answerCorrect=true then 1 else 0 end), sum(case when q.mode='REVIEW' then 1 else 0 end) from VocabularyQuestionEntity q where q.ownerId=:owner and q.studyDate is not null group by q.studyDate",Object[].class)
                .setParameter("owner",owner).getResultList();
        Map<LocalDate,Day> days=new HashMap<>();
        Map<LocalDate,Long> learnedByDate=progress.values().stream().filter(v->v.learnedDate!=null).collect(Collectors.groupingBy(v->v.learnedDate,Collectors.counting()));
        for(Object[] row:totals) { LocalDate date=(LocalDate)row[0];days.put(date,new Day(date,((Number)row[1]).longValue(),((Number)row[2]).longValue(),learnedByDate.getOrDefault(date,0L),((Number)row[3]).longValue())); }
        Map<String,Day> restored=read(p.historyJson,new TypeReference<Map<String,Day>>(){});
        for(var item:restored.values()) {
            var live=days.getOrDefault(item.date(),new Day(item.date(),0,0,0,0));
            days.put(item.date(),new Day(item.date(),live.answers()+item.answers(),live.correct()+item.correct(),learnedByDate.getOrDefault(item.date(),0L),live.reviews()+item.reviews()));
        }
        List<Day> history=new ArrayList<>();
        if(day!=null) for(int i=13;i>=0;i--) history.add(days.getOrDefault(day.minusDays(i),new Day(day.minusDays(i),0,0,0,0)));
        int streak=0;
        if(day!=null) { LocalDate cursor=days.containsKey(day)?day:day.minusDays(1); while(days.containsKey(cursor)) { streak++; cursor=cursor.minusDays(1); } }
        return new Dashboard(settings(p),books,day==null?new Day(null,0,0,0,0):days.getOrDefault(day,new Day(day,0,0,0,0)),streak,history,
                progress.values().stream().filter(v->isDue(v,day)).count(),progress.values().stream().filter(v->v.starred).count());
    }
    private List<VocabularyBookEntity> accessibleBooks(long owner) {
        return em.createQuery("select b from VocabularyBookEntity b where b.ownerId is null or b.ownerId=:owner order by b.createdAt,b.id",VocabularyBookEntity.class).setParameter("owner",owner).getResultList();
    }
    VocabularyBookEntity book(String id,long owner) {
        var b=em.find(VocabularyBookEntity.class,id);
        if(b==null || (b.ownerId!=null && b.ownerId!=owner)) throw missing(); return b;
    }
    List<VocabularyWordEntity> words(String bookId) {
        return em.createQuery("select w from VocabularyWordEntity w where w.bookId=:book order by w.sortOrder,w.id",VocabularyWordEntity.class).setParameter("book",bookId).getResultList().stream().collect(Collectors.toMap(w->w.termKey,Function.identity(),(first,next)->first,LinkedHashMap::new)).values().stream().toList();
    }
    Map<String,VocabularyProgressEntity> progress(long owner) {
        return em.createQuery("select p from VocabularyProgressEntity p where p.ownerId=:owner",VocabularyProgressEntity.class).setParameter("owner",owner).getResultList().stream().collect(Collectors.toMap(p->p.termKey, Function.identity()));
    }
    VocabularyProgressEntity wordProgress(long owner,String word) {
        var rows=em.createQuery("select p from VocabularyProgressEntity p where p.ownerId=:owner and p.termKey=:word",VocabularyProgressEntity.class).setParameter("owner",owner).setParameter("word",em.find(VocabularyWordEntity.class,word).termKey).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if(!rows.isEmpty()) return rows.get(0);
        var p=new VocabularyProgressEntity();p.id=UUID.randomUUID().toString();p.ownerId=owner;p.wordId=word;p.termKey=em.find(VocabularyWordEntity.class,word).termKey;p.updatedAt=now();em.persist(p);return p;
    }
    private boolean isDue(VocabularyProgressEntity p,LocalDate day) { return day!=null && !p.skipped && p.learningCorrect>=MASTERY_TARGET && p.dueDate!=null && !p.dueDate.isAfter(day); }
    private Book bookView(VocabularyBookEntity b,long owner,Map<String,VocabularyProgressEntity> progress,LocalDate day) {
        var words=words(b.id);var ps=words.stream().map(w->progress.get(w.termKey)).filter(Objects::nonNull).toList();
        return new Book(b.id,b.title,b.description,b.attribution,b.level,b.ownerId!=null && b.ownerId==owner,words.size(),
                ps.stream().filter(p->p.learningCorrect>=MASTERY_TARGET).count(),ps.stream().filter(p->p.learningCorrect>0 && p.learningCorrect<MASTERY_TARGET).count(),
                ps.stream().filter(p->isDue(p,day)).count(),ps.stream().filter(p->p.mistake&&!p.skipped).count(),ps.stream().filter(p->p.skipped).count(),words.stream().filter(w->progress.containsKey(w.termKey)&&!progress.get(w.termKey).wordId.equals(w.id)&&progress.get(w.termKey).learningCorrect>0).count(),0);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Next next(NextRequest request) {
        long owner=lockedOwner();var p=profile(owner);var day=today(p);book(request.bookId(),owner);
        if(!Set.of("LEARN","REVIEW","MISTAKES").contains(request.mode())) throw bad("INVALID_MODE","不支持的学习模式");
        // A single active question per account prevents parallel tabs accumulating credit for one encounter.
        var active=em.createQuery("select q from VocabularyQuestionEntity q where q.ownerId=:owner and q.answeredAt is null and q.expiresAt>:now order by q.createdAt desc",VocabularyQuestionEntity.class)
                .setParameter("owner",owner).setParameter("now",now()).getResultList();
        for(var q:active) if(q.bookId.equals(request.bookId()) && q.mode.equals(request.mode()) && ("RECALL".equals(request.style())?!q.practiceKind.equals("CHOICE"):q.practiceKind.equals("CHOICE"))) return new Next(question(q),null,-1);
        for(var q:active) q.expiresAt=now();
        var all=words(request.bookId());var ps=progress(owner);
        long learnedToday=ps.values().stream().filter(v->day.equals(v.learnedDate)).count();
        List<VocabularyWordEntity> candidates=new ArrayList<>();
        if(request.mode().equals("LEARN")) {
            var incomplete=all.stream().filter(w->!ps.containsKey(w.termKey)||(!ps.get(w.termKey).skipped&&ps.get(w.termKey).learningCorrect<MASTERY_TARGET)).toList();
            // Keep a small round-robin batch; the target counts distinct newly learned words, not answers.
            int remaining=(int)Math.max(0,p.dailyGoal-learnedToday);
            candidates.addAll(incomplete.stream().filter(w->ps.containsKey(w.termKey) && ps.get(w.termKey).lastAttemptAt!=null).toList());
            int available=Math.max(0,remaining-candidates.size());
            candidates.addAll(incomplete.stream().filter(w->!ps.containsKey(w.termKey)||ps.get(w.termKey).lastAttemptAt==null).limit(available).toList());
        } else if(request.mode().equals("REVIEW")) candidates.addAll(all.stream().filter(w->ps.containsKey(w.termKey)&&isDue(ps.get(w.termKey),day)).toList());
        else candidates.addAll(all.stream().filter(w->ps.containsKey(w.termKey)&&ps.get(w.termKey).mistake&&!ps.get(w.termKey).skipped).toList());
        if(candidates.isEmpty()) return new Next(null,request.mode().equals("REVIEW")?"今天的到期复习已完成":request.mode().equals("MISTAKES")?"当前词书没有待巩固错词":learnedToday>=p.dailyGoal?"今日新词目标已完成，可先复习或调整目标":"本词书的新词已完成或已跳过，可切换词书或复习",0);
        candidates.sort(Comparator.comparing(w->ps.containsKey(w.termKey)&&ps.get(w.termKey).lastAttemptAt!=null?ps.get(w.termKey).lastAttemptAt:Instant.MIN));
        var word=candidates.get(0);var q=new VocabularyQuestionEntity();q.id=UUID.randomUUID().toString();q.ownerId=owner;q.wordId=word.id;q.bookId=word.bookId;q.mode=request.mode();q.createdAt=now();q.expiresAt=now().plusSeconds(1800);
        List<String> distractors=read(word.distractors,new TypeReference<List<String>>(){});Collections.shuffle(distractors);
        var options=new ArrayList<Option>();var correct=new Option(UUID.randomUUID().toString(),word.meaning);options.add(correct);
        distractors.stream().limit(3).forEach(d->options.add(new Option(UUID.randomUUID().toString(),d)));Collections.shuffle(options);
        q.optionsJson=write(options);q.correctOptionId=correct.id();
        if("RECALL".equals(request.style())) {
            var progress=ps.get(word.termKey); var lesson=lessons.forWord(word);
            var patterns=lesson.collocations().stream().filter(c->c.pattern().matches("[A-Za-z .,\\'/-]+") && c.pattern().length()<=160).toList();
            boolean collocation=progress!=null && progress.spellingCorrect>0 && progress.collocationCorrect<=progress.spellingCorrect && !patterns.isEmpty();
            q.practiceKind=collocation?"COLLOCATION":"SPELLING";
            var pattern=patterns.isEmpty()?null:patterns.get(progress==null?0:progress.collocationCorrect%patterns.size());
            q.promptText=collocation?pattern.meaning():word.meaning;
            q.expectedText=collocation?pattern.pattern():word.term;
        }
        em.persist(q);return new Next(question(q),null,candidates.size());
    }
    private Question question(VocabularyQuestionEntity q) {
        var word=em.find(VocabularyWordEntity.class,q.wordId);var p=progress(q.ownerId).get(word.termKey);
        var lesson=lessons.forWord(word);
        return new Question(q.id,word.term,lesson.ipa(),word.pos,q.mode,p==null?0:p.learningCorrect,MASTERY_TARGET,
                q.practiceKind.equals("CHOICE")?read(q.optionsJson,new TypeReference<List<Option>>(){}):List.of(),q.expiresAt,
                q.practiceKind,q.promptText,p!=null&&(p.introducedAt!=null||p.lastAttemptAt!=null||p.learningCorrect>0),q.hintLevel);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Answer answer(String questionId,AnswerRequest request) {
        long owner=lockedOwner();var q=em.find(VocabularyQuestionEntity.class,questionId,LockModeType.PESSIMISTIC_WRITE);
        if(q==null || q.ownerId!=owner) throw missing();
        // Idempotent replay returns the original outcome regardless of the second submitted option.
        if(q.answeredAt!=null) {
            if("SKIPPED".equals(q.resultJson))throw new BusinessException(HttpStatus.CONFLICT,"WORD_SKIPPED","该词已跳过，不能重复提交答题");
            return read(q.resultJson,new TypeReference<Answer>(){});
        }
        if(!q.expiresAt.isAfter(now())) throw new BusinessException(HttpStatus.CONFLICT,"QUESTION_EXPIRED","题目已过期或被另一个学习页面替换，请重新取题");
        boolean choice=q.practiceKind.equals("CHOICE");
        List<Option> options=read(q.optionsJson,new TypeReference<List<Option>>(){});
        if(choice && options.stream().noneMatch(o->o.id().equals(request.optionId()))) throw bad("INVALID_OPTION","选项不属于这道题");
        if(!choice && (request.text()==null||request.text().isBlank()||hasControl(request.text())))throw bad("INVALID_RECALL","请填写英文单词或固定搭配");
        var profile=profile(owner);LocalDate day=today(profile);var p=wordProgress(owner,q.wordId);var word=em.find(VocabularyWordEntity.class,q.wordId);
        boolean correct=choice?q.correctOptionId.equals(request.optionId()):recallKey(q.expectedText).equals(recallKey(request.text())), learned=false;
        boolean delayed=!choice&&q.hintLevel==0&&p.lastViewedAt!=null&&!p.lastViewedAt.plusSeconds(60).isAfter(now());
        String evidence=choice?"RECOGNITION":!correct?"INCORRECT":q.hintLevel>0?"PROMPTED":delayed?"DELAYED":"IMMEDIATE";
        boolean credit=choice||delayed;
        if(!choice&&correct) {
            if(q.hintLevel>0)p.promptedCorrect++;
            else if(delayed)p.independentCorrect++;else p.immediateCorrect++;
            if(q.hintLevel==0){if(q.practiceKind.equals("SPELLING"))p.spellingCorrect++;else p.collocationCorrect++;}
        }
        if(q.mode.equals("REVIEW") && !isDue(p,day)) throw new BusinessException(HttpStatus.CONFLICT,"REVIEW_NOT_DUE","该词尚未到复习日期，请重新取题");
        if(correct && credit) {
            p.mistake=false;
            if(q.mode.equals("LEARN") && p.learningCorrect<MASTERY_TARGET) {
                p.learningCorrect++;
                if(p.learningCorrect==MASTERY_TARGET) { learned=true;p.learnedDate=day;p.dueDate=day.plusDays(1);p.reviewStage=0; }
            } else if(q.mode.equals("REVIEW")) {
                p.dueDate=day.plusDays(REVIEW_DAYS[Math.min(p.reviewStage,REVIEW_DAYS.length-1)]);p.reviewStage=Math.min(p.reviewStage+1,REVIEW_DAYS.length);p.lastReviewDate=day;
            }
        } else if(!correct) {
            p.wrongCount++;p.mistake=true;
            if(q.mode.equals("REVIEW")) { p.reviewStage=0;p.dueDate=day.plusDays(1);p.lastReviewDate=day; }
        }
        if(!choice&&correct&&!credit&&q.mode.equals("REVIEW")){p.dueDate=day.plusDays(1);p.lastReviewDate=day;}
        p.lastAttemptAt=now();p.updatedAt=now();if(!choice)p.lastViewedAt=now();q.answeredAt=now();q.studyDate=day;q.answerCorrect=correct;
        String message=!correct?"已加入错词巩固，本次不增加记忆次数":learned?"累计答对 4 次，已加入明日复习":q.mode.equals("MISTAKES")?"错词已巩固，学习进度和复习日期保持不变":q.mode.equals("REVIEW")?"复习完成，已安排下一次复习":"答对了，记忆次数 +1";
        if(!choice&&correct&&!credit)message=q.hintLevel>0?"提示后答对：已记录，稍后再独立回忆": "即时回忆正确：已记录，隔开其他单词后再抽查";
        var result=new Answer(q.id,word.id,correct,q.correctOptionId,word.meaning,word.exampleText,word.exampleTranslation,p.learningCorrect,MASTERY_TARGET,learned,p.dueDate,p.reviewStage,p.starred,message,evidence,q.expectedText,p.independentCorrect,p.promptedCorrect,p.immediateCorrect,p.spellingCorrect,p.collocationCorrect,lessons.forWord(word));q.resultJson=write(result);return result;
    }
    @Transactional(readOnly=true)
    public WordPage browse(String bookId,String filter,String query,int page) {
        long owner=owner();book(bookId,owner);if(page<0||page>10000) throw bad("INVALID_PAGE","页码无效");
        if(query!=null && query.length()>80) throw bad("INVALID_QUERY","搜索词太长");
        if(!Set.of("ALL","LEARNING","LEARNED","MISTAKES","STARRED","DUE","SKIPPED").contains(filter)) throw bad("INVALID_FILTER","筛选条件无效");
        var p=em.find(VocabularyProfileEntity.class,owner);LocalDate day=p==null||p.zoneId==null?null:today(p);var ps=progress(owner);
        var items=words(bookId).stream().filter(w->query==null||query.isBlank()||w.term.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT))||w.meaning.contains(query.trim()))
                .filter(w->{var v=ps.get(w.termKey);return switch(filter) {case "LEARNING"->v!=null&&v.learningCorrect>0&&v.learningCorrect<MASTERY_TARGET;case "LEARNED"->v!=null&&v.learningCorrect>=MASTERY_TARGET;case "MISTAKES"->v!=null&&v.mistake&&!v.skipped;case "SKIPPED"->v!=null&&v.skipped;case "STARRED"->v!=null&&v.starred;case "DUE"->v!=null&&isDue(v,day);default->true;};}).toList();
        return new WordPage(items.stream().skip(page*30L).limit(30).map(w->wordView(w,ps.get(w.termKey))).toList(),items.size(),page,30);
    }
    private Word wordView(VocabularyWordEntity w,VocabularyProgressEntity p) {
        var lesson=lessons.forWord(w);
        return new Word(w.id,w.term,lesson.ipa(),w.pos,w.meaning,lesson.example(),lesson.exampleTranslation(),p==null?0:p.learningCorrect,p==null?0:p.wrongCount,p!=null&&p.mistake,p!=null&&p.starred,p==null?null:p.dueDate,p==null?0:p.reviewStage,p!=null&&p.skipped,
                p==null?0:p.independentCorrect,p==null?0:p.promptedCorrect,p==null?0:p.immediateCorrect,p==null?0:p.spellingCorrect,p==null?0:p.collocationCorrect,lesson);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Word star(String wordId,boolean starred) {
        long owner=lockedOwner();var w=em.find(VocabularyWordEntity.class,wordId);if(w==null)throw missing();book(w.bookId,owner);
        var p=wordProgress(owner,wordId);p.starred=starred;p.updatedAt=now();return wordView(w,p);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Word collectTerm(String term) {
        long owner=lockedOwner();
        var matches=em.createQuery("select w from VocabularyWordEntity w join VocabularyBookEntity b on b.id=w.bookId where (b.ownerId is null or b.ownerId=:owner) and lower(w.term)=:term order by case when b.ownerId is null then 1 else 0 end, w.id",VocabularyWordEntity.class)
            .setParameter("owner",owner).setParameter("term",term.trim().toLowerCase(Locale.ROOT)).setMaxResults(1).getResultList();
        if(matches.isEmpty())throw bad("TERM_NOT_FOUND","词库中还没有这个单词，可以先为它制作一张复习卡");
        var word=matches.get(0);var p=wordProgress(owner,word.id);p.starred=true;p.updatedAt=now();return wordView(word,p);
    }
    private VocabularyQuestionEntity activeQuestion(String id,long owner) {
        var q=em.find(VocabularyQuestionEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(q==null||q.ownerId!=owner)throw missing();
        if(q.answeredAt!=null||!q.expiresAt.isAfter(now()))throw new BusinessException(HttpStatus.CONFLICT,"QUESTION_EXPIRED","题目已结束或过期，请重新取题");
        book(q.bookId,owner);return q;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Lesson lesson(String id) {
        long owner=lockedOwner();var q=activeQuestion(id,owner);var p=wordProgress(owner,q.wordId);
        if(p.introducedAt==null)p.introducedAt=now();p.lastViewedAt=now();p.updatedAt=now();
        return lessons.forWord(em.find(VocabularyWordEntity.class,q.wordId));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Hint hint(String id,int level) {
        long owner=lockedOwner();var q=activeQuestion(id,owner);q.hintLevel=Math.max(q.hintLevel,level);
        var word=em.find(VocabularyWordEntity.class,q.wordId);var lesson=lessons.forWord(word);
        String expected=q.expectedText==null?word.term:q.expectedText;
        String cue=lesson.memoryCue().replaceAll("(?i)"+java.util.regex.Pattern.quote(word.term),"这个词");
        return new Hint(q.hintLevel,level==1?cue:level==2?expected.substring(0,1)+"…":expected);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Word skipQuestion(String id) {
        long owner=lockedOwner();var q=em.find(VocabularyQuestionEntity.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(q==null||q.ownerId!=owner)throw missing();
        if(q.answeredAt!=null&&"SKIPPED".equals(q.resultJson))return wordView(em.find(VocabularyWordEntity.class,q.wordId),wordProgress(owner,q.wordId));
        q=activeQuestion(id,owner);var word=em.find(VocabularyWordEntity.class,q.wordId);var p=wordProgress(owner,word.id);
        p.skipped=true;p.updatedAt=now();q.answeredAt=now();q.resultJson="SKIPPED";
        return wordView(word,p);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Word setSkipped(String wordId,boolean skipped) {
        long owner=lockedOwner();var word=em.find(VocabularyWordEntity.class,wordId);if(word==null)throw missing();book(word.bookId,owner);
        var p=wordProgress(owner,wordId);p.skipped=skipped;p.updatedAt=now();
        if(skipped) {
            var active=em.createQuery("select q from VocabularyQuestionEntity q join VocabularyWordEntity w on w.id=q.wordId where q.ownerId=:owner and w.termKey=:key and q.answeredAt is null and q.expiresAt>:now",VocabularyQuestionEntity.class)
                    .setParameter("owner",owner).setParameter("key",word.termKey).setParameter("now",now()).getResultList();
            active.forEach(q->q.expiresAt=now());
        }
        return wordView(word,p);
    }
    private String recallKey(String text) {
        return VocabularyTermKey.of(text).replaceAll("\\bsomebody\\b|\\bsomeone\\b","sb")
                .replaceAll("\\bsomething\\b","sth").replaceAll("[.,()]","").replaceAll("\\s+"," ").trim();
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Book importBook(ImportRequest request) {
        long owner=lockedOwner();
        // Unversioned original imports remain compatible; future schemas must not be silently reinterpreted.
        if(request.schemaVersion()!=null && request.schemaVersion()!=1)throw bad("IMPORT_SCHEMA","不支持此词书格式版本");
        if(!request.rightsConfirmed())throw bad("RIGHTS_REQUIRED","请确认你有权使用这些词条、释义和例句");
        if(request.words()==null||request.words().size()<4||request.words().size()>500)throw bad("IMPORT_SIZE","每本词书需要 4 至 500 个词条");
        long count=em.createQuery("select count(b) from VocabularyBookEntity b where b.ownerId=:owner",Long.class).setParameter("owner",owner).getSingleResult();
        if(count>=20)throw bad("BOOK_LIMIT","每个账号最多导入 20 本词书");
        Map<String,ImportWord> unique=new LinkedHashMap<>();
        for(var w:request.words()){if(w==null||w.term()==null)throw bad("INVALID_WORD","词条不能为空");unique.putIfAbsent(VocabularyTermKey.of(w.term()),w);}
        if(unique.size()<4)throw bad("DUPLICATE_TERM","自动去重后，每本词书至少需要 4 个不同单词");
        for(var w:unique.values()) {
            if(w==null) throw bad("INVALID_WORD","词条不能为空");
            if((w.memoryCue()!=null&&hasControl(w.memoryCue()))||(w.usageNote()!=null&&hasControl(w.usageNote()))
                    ||(w.collocations()!=null&&w.collocations().stream().anyMatch(c->hasControl(c.pattern())||hasControl(c.meaning())||(c.note()!=null&&hasControl(c.note())))))throw bad("INVALID_TEXT","记忆提示和搭配不能包含控制字符");
            if(java.util.stream.Stream.of(w.term(),w.ipa(),w.meaning(),w.example(),w.exampleTranslation()).anyMatch(this::hasControl)
                    || w.distractors().stream().anyMatch(this::hasControl)) throw bad("INVALID_TEXT","单词、释义、音标和例句不能包含控制字符");
            if(write(w.distractors()).length()>1500) throw bad("OPTIONS_TOO_LONG","干扰项转义后的内容过长，请缩短释义");
            if(normalize(w.meaning()).isEmpty() || w.distractors().stream().anyMatch(d->normalize(d).isEmpty())) throw bad("INVALID_MEANING","释义必须包含实际文字");
            if(!VocabularyTermKey.of(w.term()).matches("[a-z][a-z '\\-]{0,79}"))throw bad("INVALID_TERM","单词只能包含英文字母、空格、连字符和撇号");
            if(w.distractors()==null||w.distractors().size()<3||w.distractors().size()>8)throw bad("INVALID_DISTRACTORS","每词需提供 3 至 8 个不同且不重叠的错误释义");
            Set<String> labels=new HashSet<>();labels.add(normalize(w.meaning()));
            for(String d:w.distractors())if(!labels.add(normalize(d)))throw bad("AMBIGUOUS_OPTIONS","正确释义与干扰项不可重复");
        }
        var b=new VocabularyBookEntity();b.id=UUID.randomUUID().toString();b.ownerId=owner;b.title=request.title().trim();b.description=request.description().trim();b.attribution=request.attribution().trim();b.level="自建词书";b.createdAt=now();em.persist(b);
        int index=0;for(var input:unique.values()) {var w=new VocabularyWordEntity();w.id=UUID.randomUUID().toString();w.bookId=b.id;w.term=input.term().trim();w.ipa=input.ipa().trim();w.pos=input.pos();w.meaning=input.meaning().trim();w.exampleText=input.example().trim();w.exampleTranslation=input.exampleTranslation().trim();w.distractors=write(input.distractors().stream().map(String::trim).toList());w.sortOrder=index++;if(input.memoryCue()!=null||input.usageNote()!=null||input.collocations()!=null)w.lessonJson=write(input);em.persist(w);}
        em.flush();var profile=profile(owner);var view=bookView(b,owner,progress(owner),profile.zoneId==null?null:today(profile));
        return new Book(view.id(),view.title(),view.description(),view.attribution(),view.level(),view.owned(),view.totalWords(),view.learned(),view.learning(),view.due(),view.mistakes(),view.skipped(),view.shared(),request.words().size()-unique.size());
    }
    private boolean hasControl(String value) { return value.codePoints().anyMatch(Character::isISOControl); }
    private String normalize(String value) {return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s\\p{P}]","").toLowerCase(Locale.ROOT);}
    String write(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Vocabulary serialization failed",e);}}
    <T>T read(String value,TypeReference<T> type) {try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Vocabulary data is invalid",e);}}
    private BusinessException missing() {return new BusinessException(HttpStatus.NOT_FOUND,"VOCAB_NOT_FOUND","词书、单词或学习记录不存在");}
    private BusinessException bad(String code,String message) {return new BusinessException(HttpStatus.BAD_REQUEST,code,message);}
}
