package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.BusinessException;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class PersonalWorkflowService {
    public record Preferences(@NotNull Boolean groups,@NotNull Boolean contacts,@NotNull Boolean mail,@NotNull Boolean study,
                              @Min(5) @Max(7000) int weeklyTarget,@NotEmpty @Size(max=7) List<@NotNull @Min(0) @Max(6) Integer> studyDays,
                              @NotBlank @Size(max=60) String zoneId,@Min(0) long revision) {}
    public record Reminder(@NotNull LocalDate date,@Min(0) @Max(365) int repeatDays) {}
    private final JdbcTemplate db; private final CurrentUserService current; private final ObjectMapper mapper; private final NoteService notes;
    @org.springframework.beans.factory.annotation.Autowired private LiveUpdateService live;
    public PersonalWorkflowService(JdbcTemplate db,CurrentUserService current,ObjectMapper mapper,NoteService notes){this.db=db;this.current=current;this.mapper=mapper;this.notes=notes;}
    public Preferences defaults(){return new Preferences(true,true,true,true,70,List.of(0,1,2,3,4,5,6),"UTC",0);}
    @Transactional(readOnly=true) public Preferences get(){return get(current.requireCurrent().getId());}
    public Preferences get(long owner){return db.query("SELECT payload,revision FROM personal_preference WHERE owner_id=?",(r,i)->{try {var p=mapper.readValue(r.getString(1),Preferences.class);return new Preferences(p.groups(),p.contacts(),p.mail(),p.study(),p.weeklyTarget(),p.studyDays(),p.zoneId(),r.getLong(2));}catch(Exception e){throw new IllegalStateException("Invalid personal preferences",e);}},owner).stream().findFirst().orElse(defaults());}
    @Transactional public Preferences save(Preferences p){
        zone(p.zoneId()); if(new HashSet<>(p.studyDays()).size()!=p.studyDays().size())throw bad("STUDY_DAYS_INVALID","学习日期不能重复");
        long owner=current.requireCurrent().getId();db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",Long.class,owner);
        var existing=db.queryForList("SELECT revision FROM personal_preference WHERE owner_id=?",Long.class,owner);
        long revision=existing.isEmpty()?0:existing.get(0);if(revision!=p.revision())throw new BusinessException(HttpStatus.CONFLICT,"PREFERENCE_CONFLICT","设置已在其他页面更新，请刷新后重试");
        try {String json=mapper.writeValueAsString(p);if(existing.isEmpty())db.update("INSERT INTO personal_preference(owner_id,payload,revision) VALUES (?,?,?)",owner,json,1);else db.update("UPDATE personal_preference SET payload=?,revision=? WHERE owner_id=?",json,revision+1,owner);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}live.changed(List.of(owner));return get(owner);
    }
    public static ZoneId zone(String value){try{return ZoneId.of(value);}catch(Exception e){throw bad("TIME_ZONE_INVALID","无法识别浏览器时区");}}
    @Transactional(readOnly=true) public Map<String,Object> reminder(String id){notes.requireOwnEntity(id);return db.queryForList("SELECT due_date,repeat_days FROM note_reminder WHERE note_id=? AND owner_id=?",id,current.requireCurrent().getId()).stream().findFirst().map(r->Map.<String,Object>of("date",r.get("due_date").toString(),"repeatDays",r.get("repeat_days"))).orElse(Map.of());}
    @Transactional public void remind(String id,Reminder input){long owner=current.requireCurrent().getId();db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",Long.class,owner);notes.requireOwnEntity(id);if(input.date().isAfter(LocalDate.now().plusYears(5)))throw bad("REMINDER_DATE_INVALID","复习日期最多安排到五年后");db.update("DELETE FROM note_reminder WHERE note_id=? AND owner_id=?",id,owner);db.update("INSERT INTO note_reminder(note_id,owner_id,due_date,repeat_days) VALUES (?,?,?,?)",id,owner,java.sql.Date.valueOf(input.date()),input.repeatDays());}
    @Transactional public void finish(String id){long owner=current.requireCurrent().getId();db.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",Long.class,owner);notes.requireOwnEntity(id);var rows=db.queryForList("SELECT repeat_days FROM note_reminder WHERE note_id=? AND owner_id=?",Integer.class,id,owner);if(rows.isEmpty())return;int days=rows.get(0);if(days==0)clear(id);else db.update("UPDATE note_reminder SET due_date=? WHERE note_id=? AND owner_id=?",java.sql.Date.valueOf(today(owner).plusDays(days)),id,owner);}
    @Transactional public void clear(String id){notes.requireOwnEntity(id);db.update("DELETE FROM note_reminder WHERE note_id=? AND owner_id=?",id,current.requireCurrent().getId());}
    public LocalDate today(long owner){var zones=db.queryForList("SELECT zone_id FROM vocabulary_profile WHERE owner_id=? AND zone_id IS NOT NULL AND zone_id<>''",String.class,owner);return LocalDate.now(zone(zones.isEmpty()?get(owner).zoneId():zones.get(0)));}
    @Transactional(readOnly=true) public List<Map<String,Object>> due(){return due(current.requireCurrent().getId());}
    public List<Map<String,Object>> due(long owner){return db.query("SELECT n.id,n.title,r.due_date,r.repeat_days FROM note_reminder r JOIN note n ON n.id=r.note_id WHERE r.owner_id=? AND n.owner_id=? AND n.deleted_at IS NULL AND r.due_date<=? ORDER BY r.due_date,n.id LIMIT 50",(r,i)->Map.<String,Object>of("id",r.getString(1),"title",r.getString(2),"date",r.getDate(3).toLocalDate(),"repeatDays",r.getInt(4)),owner,owner,java.sql.Date.valueOf(today(owner)));}
    private static BusinessException bad(String code,String message){return new BusinessException(HttpStatus.BAD_REQUEST,code,message);}
}
