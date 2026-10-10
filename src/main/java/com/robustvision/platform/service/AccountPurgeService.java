package com.robustvision.platform.service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Instant;
import java.util.*;
@Service
public class AccountPurgeService {
    private final JdbcTemplate db; private final FileService files;private final PersonalTrainingService training;
    public AccountPurgeService(JdbcTemplate db,FileService files,PersonalTrainingService training){this.db=db;this.files=files;this.training=training;}
    public void erasePrivate(long owner){
        // Dissolved groups and delivered conversations remain shared history.
        db.update("DELETE FROM workspace_member_permission WHERE workspace_member_id IN (SELECT id FROM workspace_member WHERE user_id=?)",owner);
        db.update("DELETE FROM workspace_member WHERE user_id=?",owner);
        db.update("DELETE FROM group_invitation WHERE target_id=? OR actor_id=?",owner,owner);
        db.update("DELETE FROM group_report WHERE reporter_id=?",owner);
        db.update("UPDATE contact_link SET low_remark='',low_pinned=FALSE,low_muted=FALSE,low_read_through=0,low_cleared_through=0 WHERE low_user_id=?",owner);
        db.update("UPDATE contact_link SET high_remark='',high_pinned=FALSE,high_muted=FALSE,high_read_through=0,high_cleared_through=0 WHERE high_user_id=?",owner);
        db.update("DELETE FROM note_share WHERE note_id IN (SELECT id FROM note WHERE owner_id=?)",owner);
        db.update("DELETE FROM note_reference WHERE note_id IN (SELECT id FROM note WHERE owner_id=?)",owner);
        db.update("DELETE FROM vocabulary_question WHERE owner_id=?",owner);
        db.update("DELETE FROM vocabulary_progress WHERE owner_id=?",owner);
        db.update("DELETE FROM vocabulary_word WHERE book_id IN (SELECT id FROM vocabulary_book WHERE owner_id=?)",owner);
        for(String table:List.of("note_reminder","note_link","note_version","note","knowledge_entry","knowledge_topic","learning_record","workspace_shortcut","personal_ai_setting","personal_ai_memory","personal_ai_usage","personal_recognition_result","vocabulary_skill","vocabulary_profile","vocabulary_book","workspace_preference","personal_preference","account_challenge","account_security"))db.update("DELETE FROM "+table+" WHERE owner_id=?",owner);
        db.update("DELETE FROM inference_task WHERE requested_by=?",owner);
        db.update("INSERT INTO account_cleanup(id,owner_id,kind,resource,attempts,retry_at) VALUES (?,?, 'TRAINING',?,0,?)",UUID.randomUUID().toString(),owner,Long.toString(owner),java.sql.Timestamp.from(Instant.now()));
        // Already delivered messages and shared files belong to conversation history.
        // Remove only private objects that no remaining message or experiment references.
        var files=db.query("SELECT f.id,f.storage_path FROM file_asset f WHERE f.owner_id=? AND NOT EXISTS(SELECT 1 FROM message_attachment a WHERE a.file_id=f.id) AND NOT EXISTS(SELECT 1 FROM inference_task t WHERE t.input_file_id=f.id OR t.output_file_id=f.id)",(r,i)->Map.entry(r.getString(1),r.getString(2)),owner);
        for(var f:files){db.update("INSERT INTO account_cleanup(id,owner_id,kind,resource,attempts,retry_at) VALUES (?,?, 'OBJECT',?,0,?)",UUID.randomUUID().toString(),owner,f.getValue(),java.sql.Timestamp.from(Instant.now()));db.update("DELETE FROM file_asset WHERE id=? AND owner_id=?",f.getKey(),owner);}
    }
    @Scheduled(fixedDelay=60000,initialDelay=60000) @Transactional
    public void cleanup(){
        var jobs=db.queryForList("SELECT id,owner_id,kind,resource,attempts FROM account_cleanup WHERE retry_at<=? ORDER BY retry_at LIMIT 20 FOR UPDATE",java.sql.Timestamp.from(Instant.now()));
        for(var job:jobs)try{if("TRAINING".equals(job.get("kind")))training.purgeOwner(((Number)job.get("owner_id")).longValue());else files.deletePrivateObject(job.get("resource").toString());db.update("DELETE FROM account_cleanup WHERE id=?",job.get("id"));}
        catch(RuntimeException e){db.update("UPDATE account_cleanup SET attempts=attempts+1,retry_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().plusSeconds(300)),job.get("id"));org.slf4j.LoggerFactory.getLogger(getClass()).warn("Private object cleanup deferred");}
    }
}
