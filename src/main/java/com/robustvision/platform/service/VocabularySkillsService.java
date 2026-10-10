package com.robustvision.platform.service;
import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
@Service
public class VocabularySkillsService {
 private final JdbcTemplate db;private final CurrentUserService current;private final VocabularyService vocabulary;
 public VocabularySkillsService(JdbcTemplate db,CurrentUserService current,@org.springframework.context.annotation.Lazy VocabularyService vocabulary){this.db=db;this.current=current;this.vocabulary=vocabulary;}
 public record Skill(String term,String kind,String detail,int correct,int wrong,int independent,Instant lastAttempt){}
 public record Daily(String date,long answers,long correct){}
 public record Stats(List<Daily> days,List<Skill> weakPoints,long answers,long correct){}
 public void record(VocabularyWordEntity word,VocabularyQuestionEntity q,boolean correct,boolean independent,Instant now){
  String kind=q.practiceKind.equals("CHOICE")?"MEANING":q.practiceKind;
  String detail=kind.equals("MEANING")?(q.skillDetail==null?word.meaning:q.skillDetail):kind.equals("COLLOCATION")?q.expectedText:word.term;
  if(detail.length()>500)detail=detail.substring(0,500);
  String key=AccountSecurityService.hash(kind+"\n"+detail);var rows=db.queryForList("SELECT id FROM vocabulary_skill WHERE owner_id=? AND term_key=? AND skill_key=?",q.ownerId,word.termKey,key);
  if(rows.isEmpty())db.update("INSERT INTO vocabulary_skill(id,owner_id,term_key,skill_key,kind,detail,correct_count,wrong_count,independent_count,last_attempt) VALUES (?,?,?,?,?,?,0,0,0,?)",UUID.randomUUID().toString(),q.ownerId,word.termKey,key,kind,detail,Timestamp.from(now));
  db.update("UPDATE vocabulary_skill SET correct_count=correct_count+?,wrong_count=wrong_count+?,independent_count=independent_count+?,last_attempt=? WHERE owner_id=? AND term_key=? AND skill_key=?",correct?1:0,correct?0:1,correct&&independent?1:0,Timestamp.from(now),q.ownerId,word.termKey,key);
 }
 public List<Skill> export(long owner){return db.query("SELECT term_key,kind,detail,correct_count,wrong_count,independent_count,last_attempt FROM vocabulary_skill WHERE owner_id=? ORDER BY term_key,kind,skill_key LIMIT 100000",(r,i)->new Skill(r.getString(1),r.getString(2),r.getString(3),r.getInt(4),r.getInt(5),r.getInt(6),r.getTimestamp(7).toInstant()),owner);}
 public void restore(long owner,List<Skill> items){if(items==null)return;if(items.size()>100000)throw bad();for(var s:items){
  if(s==null||s.term()==null||s.term().length()>100||s.detail()==null||s.detail().length()>500||!Set.of("MEANING","SPELLING","LISTENING","COLLOCATION").contains(s.kind())||s.correct()<0||s.wrong()<0||s.independent()<0||s.independent()>s.correct()||s.correct()>10000000||s.wrong()>10000000||s.lastAttempt()==null||s.lastAttempt().isAfter(Instant.now().plusSeconds(86400)))throw bad();
  if(db.queryForObject("SELECT COUNT(*) FROM vocabulary_progress WHERE owner_id=? AND term_key=?",Integer.class,owner,s.term())==0)throw bad();
  String key=AccountSecurityService.hash(s.kind()+"\n"+s.detail());var rows=db.queryForList("SELECT id,correct_count,wrong_count,independent_count,last_attempt FROM vocabulary_skill WHERE owner_id=? AND term_key=? AND skill_key=?",owner,s.term(),key);
  if(rows.isEmpty())db.update("INSERT INTO vocabulary_skill(id,owner_id,term_key,skill_key,kind,detail,correct_count,wrong_count,independent_count,last_attempt) VALUES (?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),owner,s.term(),key,s.kind(),s.detail(),s.correct(),s.wrong(),s.independent(),Timestamp.from(s.lastAttempt()));
  else {var r=rows.get(0);db.update("UPDATE vocabulary_skill SET correct_count=?,wrong_count=?,independent_count=?,last_attempt=? WHERE id=?",Math.max(s.correct(),((Number)r.get("correct_count")).intValue()),Math.max(s.wrong(),((Number)r.get("wrong_count")).intValue()),Math.max(s.independent(),((Number)r.get("independent_count")).intValue()),Timestamp.from(s.lastAttempt().isAfter(JdbcTime.instant(r.get("last_attempt")))?s.lastAttempt():JdbcTime.instant(r.get("last_attempt"))),r.get("id"));}
 }}
 private BusinessException bad(){return new BusinessException(HttpStatus.BAD_REQUEST,"VOCABULARY_SKILL_INVALID","备份中的学习分项记录无效");}
 @Transactional(readOnly=true) public Stats stats(int count){if(!Set.of(7,30,90).contains(count))throw bad();long owner=current.requireCurrent().getId();var profile=vocabulary.profile(owner);LocalDate day=vocabulary.now().atZone(profile.zoneId==null?ZoneOffset.UTC:ZoneId.of(profile.zoneId)).toLocalDate();Map<String,Daily> dates=new HashMap<>();
  db.query("SELECT study_date,COUNT(*),SUM(CASE WHEN answer_correct=TRUE THEN 1 ELSE 0 END) FROM vocabulary_question WHERE owner_id=? AND study_date>=? GROUP BY study_date",r->{dates.put(r.getDate(1).toString(),new Daily(r.getDate(1).toString(),r.getLong(2),r.getLong(3)));},owner,java.sql.Date.valueOf(day.minusDays(count-1)));
  try {var restored=vocabulary.read(profile.historyJson,new com.fasterxml.jackson.core.type.TypeReference<Map<String,com.robustvision.platform.dto.VocabularyDtos.Day>>(){});for(var d:restored.values()){var old=dates.getOrDefault(d.date().toString(),new Daily(d.date().toString(),0,0));dates.put(d.date().toString(),new Daily(d.date().toString(),old.answers()+d.answers(),old.correct()+d.correct()));}}catch(Exception ignored){}
  List<Daily> days=new ArrayList<>();for(int i=count-1;i>=0;i--){String key=day.minusDays(i).toString();days.add(dates.getOrDefault(key,new Daily(key,0,0)));}
  var weak=db.query("SELECT term_key,kind,detail,correct_count,wrong_count,independent_count,last_attempt FROM vocabulary_skill WHERE owner_id=? AND wrong_count>0 ORDER BY (wrong_count-correct_count) DESC,last_attempt DESC LIMIT 40",(r,i)->new Skill(r.getString(1),r.getString(2),r.getString(3),r.getInt(4),r.getInt(5),r.getInt(6),r.getTimestamp(7).toInstant()),owner);
  return new Stats(days,weak,days.stream().mapToLong(Daily::answers).sum(),days.stream().mapToLong(Daily::correct).sum());
 }
}
