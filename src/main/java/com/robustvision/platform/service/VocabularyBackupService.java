package com.robustvision.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.VocabularyDtos.*;
import jakarta.persistence.EntityManager;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/** Portable owner-only snapshots. Restore merges monotonically and never adopts foreign IDs. */
@Service
public class VocabularyBackupService {
    @org.springframework.beans.factory.annotation.Autowired private VocabularySkillsService skills;
    public static final int MAX_FILE_BYTES=20*1024*1024, MAX_JSON_BYTES=64*1024*1024;
    private final VocabularyService service;
    private final VocabularyLessons lessons;
    private final EntityManager em;
    private final ObjectMapper json;
    private final Validator validator;
    public VocabularyBackupService(VocabularyService service,VocabularyLessons lessons,EntityManager em,ObjectMapper json,Validator validator) {
        this.service=service;this.lessons=lessons;this.em=em;this.json=json;this.validator=validator;
    }
    public record BookSnapshot(@NotBlank @Size(max=36) String originalId,@NotNull @Valid ImportRequest contents) {}
    public record ProgressSnapshot(@NotBlank @Size(max=80) String term,@Min(0) @Max(4) int learningCorrect,
            @Min(0) @Max(5) int reviewStage,@Min(0) @Max(10000000) int wrongCount,boolean mistake,boolean starred,
            LocalDate dueDate,LocalDate learnedDate,Instant lastAttemptAt,LocalDate lastReviewDate,boolean skipped,
            @Min(0) @Max(10000000) int independentCorrect,@Min(0) @Max(10000000) int promptedCorrect,
            @Min(0) @Max(10000000) int immediateCorrect,@Min(0) @Max(10000000) int spellingCorrect,
            @Min(0) @Max(10000000) int collocationCorrect,Instant introducedAt,Instant lastViewedAt,Instant updatedAt) {}
    public record Backup(@Pattern(regexp="PKB_VOCABULARY") String format,@Min(1) @Max(2) int schemaVersion,
            @NotNull Instant exportedAt,@NotNull Settings settings,
            @NotNull @Size(max=20) List<@NotNull @Valid BookSnapshot> books,
            @NotNull @Size(max=30000) List<@NotNull @Valid ProgressSnapshot> progress,
            @NotNull @Size(max=20000) List<@NotNull Day> history, @Size(max=100000) List<VocabularySkillsService.Skill> skills) {
        public Backup(String format,int schemaVersion,Instant exportedAt,Settings settings,List<BookSnapshot> books,List<ProgressSnapshot> progress,List<Day> history){this(format,schemaVersion,exportedAt,settings,books,progress,history,List.of());}
    }
    public record Restored(int booksImported,int booksMerged,int wordsRestored,int progressMerged,int duplicateProgressRemoved) {}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public byte[] exportFile() {
        long owner=service.lockedOwner();var profile=service.profile(owner);
        var books=ownBooks(owner).stream().map(b->new BookSnapshot(b.id,portable(b))).toList();
        var progress=service.progress(owner).values().stream().map(p->{
            var w=em.find(VocabularyWordEntity.class,p.wordId);
            return new ProgressSnapshot(w.term,p.learningCorrect,p.reviewStage,p.wrongCount,p.mistake,p.starred,p.dueDate,p.learnedDate,p.lastAttemptAt,p.lastReviewDate,p.skipped,p.independentCorrect,p.promptedCorrect,p.immediateCorrect,p.spellingCorrect,p.collocationCorrect,p.introducedAt,p.lastViewedAt,p.updatedAt);
        }).toList();
        var backup=new Backup("PKB_VOCABULARY",2,service.now(),service.settings(profile),books,progress,history(owner,profile).values().stream().sorted(Comparator.comparing(Day::date)).toList(),skills.export(owner));
        try(var output=new ByteArrayOutputStream()) {
            try(var gzip=new GZIPOutputStream(output)){json.writeValue(gzip,backup);}
            return output.toByteArray();
        } catch(IOException error){throw new IllegalStateException("Cannot export vocabulary",error);}
    }
    private ImportRequest portable(VocabularyBookEntity book) {
        // Consent is a property of this authenticated export/restore workflow, not a shared author grant.
        return new ImportRequest(book.title,book.description,book.attribution,true,service.words(book.id).stream().map(lessons::portableWord).toList(),1);
    }
    private List<VocabularyBookEntity> ownBooks(long owner) {
        return em.createQuery("select b from VocabularyBookEntity b where b.ownerId=:owner order by b.createdAt,b.id",VocabularyBookEntity.class).setParameter("owner",owner).getResultList();
    }
    private String fingerprint(ImportRequest request) {
        var words=request.words().stream().sorted(Comparator.comparing(w->VocabularyTermKey.of(w.term()))).toList();
        return service.write(new ImportRequest(request.title().trim(),request.description().trim(),request.attribution().trim(),true,words,1));
    }
    private Map<LocalDate,Day> history(long owner,VocabularyProfileEntity profile) {
        Map<LocalDate,Day> result=new TreeMap<>();
        Map<String,Day> stored=service.read(profile.historyJson,new TypeReference<Map<String,Day>>(){});
        for(var day:stored.values())result.put(day.date(),day);
        var rows=em.createQuery("select q.studyDate,count(q),sum(case when q.answerCorrect=true then 1 else 0 end),sum(case when q.mode='REVIEW' then 1 else 0 end) from VocabularyQuestionEntity q where q.ownerId=:owner and q.studyDate is not null group by q.studyDate",Object[].class).setParameter("owner",owner).getResultList();
        for(var row:rows) {
            var date=(LocalDate)row[0];var old=result.getOrDefault(date,new Day(date,0,0,0,0));
            result.put(date,new Day(date,old.answers()+((Number)row[1]).longValue(),old.correct()+((Number)row[2]).longValue(),0,old.reviews()+((Number)row[3]).longValue()));
        }
        return result;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Restored restore(MultipartFile file,boolean confirmed) {
        if(!confirmed)throw bad("请确认将备份内容合并到当前账户，并有权使用其中的私有词书");
        if(file.isEmpty()||file.getSize()>MAX_FILE_BYTES)throw bad("备份文件为空或超过 20 MB");
        Backup backup;
        try(var raw=new BufferedInputStream(file.getInputStream())) {
            raw.mark(2);int first=raw.read(),second=raw.read();raw.reset();
            try(var input=first==0x1f&&second==0x8b?new GZIPInputStream(raw):raw) {
                byte[] data=input.readNBytes(MAX_JSON_BYTES+1);
                if(data.length>MAX_JSON_BYTES)throw bad("备份解压后不能超过 64 MB");
                backup=json.readValue(data,Backup.class);
            }
        } catch(BusinessException expected){throw expected;}catch(IOException invalid){throw bad("备份文件损坏或格式无效");}
        if(backup==null||!"PKB_VOCABULARY".equals(backup.format())||backup.schemaVersion()<1||backup.schemaVersion()>2||!validator.validate(backup).isEmpty())throw bad("备份版本或内容无效");
        if(backup.settings().dailyGoal()<1||backup.settings().dailyGoal()>100||
                (backup.settings().zoneId()!=null&&!ZoneId.getAvailableZoneIds().contains(backup.settings().zoneId())))throw bad("备份学习设置无效");
        if(backup.exportedAt().isAfter(service.now().plusSeconds(300)))throw bad("备份时间不能在未来");
        long owner=service.lockedOwner();var profile=service.profile(owner);
        var owned=ownBooks(owner);Map<String,String> fingerprints=new HashMap<>(),ids=new HashMap<>();
        for(var book:owned)fingerprints.putIfAbsent(fingerprint(portable(book)),book.id);
        int imported=0,merged=0,words=0;
        for(var snapshot:backup.books()) {
            String key=fingerprint(snapshot.contents()),id=fingerprints.get(key);
            if(id==null) {
                var contents=snapshot.contents();
                var book=service.importBook(new ImportRequest(contents.title(),contents.description(),contents.attribution(),true,contents.words(),1));
                id=book.id();fingerprints.put(key,id);imported++;words+=book.totalWords();
            } else merged++;
            ids.put(snapshot.originalId(),id);
        }
        Map<String,ProgressSnapshot> unique=new LinkedHashMap<>();
        for(var item:backup.progress()) {
            if(!item.term().matches("[A-Za-z][A-Za-z '\\-]{0,79}"))throw bad("备份含无效的单词");
            for(var date:Arrays.asList(item.dueDate(),item.learnedDate(),item.lastReviewDate()))if(date!=null&&(date.getYear()<1970||date.getYear()>9999))throw bad("备份含无效学习日期");
            String key=VocabularyTermKey.of(item.term());var previous=unique.get(key);
            if(previous==null||instant(item.updatedAt()).isAfter(instant(previous.updatedAt())))unique.put(key,item);
        }
        var existingKeys=new HashSet<>(service.progress(owner).keySet());
        for(var item:unique.values()) {
            var matches=em.createQuery("select w from VocabularyWordEntity w join VocabularyBookEntity b on b.id=w.bookId where w.termKey=:key and (b.ownerId is null or b.ownerId=:owner) order by case when b.ownerId is null then 1 else 0 end,w.id",VocabularyWordEntity.class)
                    .setParameter("key",VocabularyTermKey.of(item.term())).setParameter("owner",owner).setMaxResults(1).getResultList();
            if(matches.isEmpty())throw bad("找不到备份单词「"+item.term()+"」，请同时恢复对应私有词书");
            var p=service.wordProgress(owner,matches.get(0).id);
            boolean newer=!existingKeys.contains(p.termKey)||instant(item.updatedAt()).isAfter(instant(p.updatedAt));
            p.learningCorrect=Math.max(p.learningCorrect,item.learningCorrect());p.wrongCount=Math.max(p.wrongCount,item.wrongCount());
            p.independentCorrect=Math.max(p.independentCorrect,item.independentCorrect());p.promptedCorrect=Math.max(p.promptedCorrect,item.promptedCorrect());
            p.immediateCorrect=Math.max(p.immediateCorrect,item.immediateCorrect());p.spellingCorrect=Math.max(p.spellingCorrect,item.spellingCorrect());p.collocationCorrect=Math.max(p.collocationCorrect,item.collocationCorrect());
            if(newer){p.reviewStage=item.reviewStage();p.mistake=item.mistake();p.starred=item.starred();p.skipped=item.skipped();p.dueDate=item.dueDate();p.lastReviewDate=item.lastReviewDate();}
            p.lastAttemptAt=max(p.lastAttemptAt,item.lastAttemptAt());p.introducedAt=min(p.introducedAt,item.introducedAt());p.lastViewedAt=max(p.lastViewedAt,item.lastViewedAt());
            if(p.learnedDate==null)p.learnedDate=item.learnedDate();p.updatedAt=service.now();
        }
        var currentHistory=history(owner,profile);Map<String,Day> extra=service.read(profile.historyJson,new TypeReference<Map<String,Day>>(){});
        for(var day:backup.history()) {
            if(day.date()==null||day.date().isAfter(service.now().atZone(ZoneOffset.UTC).toLocalDate().plusDays(1))||day.date().getYear()<1970||day.answers()<0||day.answers()>10000000||day.correct()<0||day.correct()>day.answers()||day.reviews()<0||day.reviews()>day.answers())throw bad("备份学习记录无效");
            var live=currentHistory.getOrDefault(day.date(),new Day(day.date(),0,0,0,0));var old=extra.getOrDefault(day.date().toString(),new Day(day.date(),0,0,0,0));
            var added=new Day(day.date(),old.answers()+Math.max(0,day.answers()-live.answers()),old.correct()+Math.max(0,day.correct()-live.correct()),0,old.reviews()+Math.max(0,day.reviews()-live.reviews()));
            extra.put(day.date().toString(),added);currentHistory.put(day.date(),new Day(day.date(),Math.max(live.answers(),day.answers()),Math.max(live.correct(),day.correct()),0,Math.max(live.reviews(),day.reviews())));
        }
        profile.historyJson=service.write(extra);
        var input=backup.settings();String selected=ids.getOrDefault(input.selectedBookId(),input.selectedBookId());
        if(selected!=null) {var book=em.find(VocabularyBookEntity.class,selected);if(book==null||(book.ownerId!=null&&book.ownerId!=owner))selected=profile.selectedBookId;}
        if(profile.zoneId==null&&input.zoneId()!=null)service.updateSettings(new SettingsRequest(input.zoneId(),input.dailyGoal(),selected));
        else if(profile.selectedBookId==null)profile.selectedBookId=selected;
        em.createQuery("update VocabularyQuestionEntity q set q.expiresAt=:now where q.ownerId=:owner and q.answeredAt is null").setParameter("now",service.now()).setParameter("owner",owner).executeUpdate();
        profile.updatedAt=service.now();em.flush();skills.restore(owner,backup.skills());return new Restored(imported,merged,words,unique.size(),backup.progress().size()-unique.size());
    }
    private Instant instant(Instant value){return value==null?Instant.EPOCH:value;}
    private Instant max(Instant first,Instant second){return first==null?second:second==null?first:first.isAfter(second)?first:second;}
    private Instant min(Instant first,Instant second){return first==null?second:second==null?first:first.isBefore(second)?first:second;}
    private BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,"VOCAB_BACKUP_INVALID",message);}
}
