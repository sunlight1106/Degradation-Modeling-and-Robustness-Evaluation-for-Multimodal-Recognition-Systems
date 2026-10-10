package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class ModerationService {
    private final JdbcTemplate db;
    private final UserRepository users;
    private final CurrentUserService current;
    private final WorkspaceService groups;
    private final LiveUpdateService live;
    private final com.robustvision.platform.repository.RoleRepository roles;
    public ModerationService(JdbcTemplate db,UserRepository users,CurrentUserService current,WorkspaceService groups,LiveUpdateService live,com.robustvision.platform.repository.RoleRepository roles){
        this.db=db;this.users=users;this.current=current;this.groups=groups;this.live=live;this.roles=roles;
    }
    private static BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,"MODERATION_INVALID",message);}
    private static BusinessException denied(){return new BusinessException(HttpStatus.FORBIDDEN,"MODERATION_DENIED","无权访问或处理此举报");}
    private static Timestamp now(){return Timestamp.from(Instant.now());}
    private UserEntity reviewer(){var u=current.requireCurrent();if(!current.hasPermission(u,"moderation:review"))throw denied();return u;}
    private String text(String s,int max){if(s==null||s.isBlank()||s.trim().length()>max)throw bad("请填写原因，且不要超过 "+max+" 字");return s.trim();}
    private Map<String,Object> row(String table,String id,boolean lock){
        var rows=db.queryForList("SELECT * FROM "+table+" WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"MODERATION_NOT_FOUND","记录不存在");return rows.get(0);
    }
    private long number(Map<String,Object> r,String key){return ((Number)r.get(key)).longValue();}
    private Map<String,Object> view(Map<String,Object> row){
        var result=new LinkedHashMap<>(row);
        result.replaceAll((key,value)->value instanceof Timestamp || value instanceof java.time.LocalDateTime || value instanceof java.time.OffsetDateTime?JdbcTime.instant(value):value);
        return result;
    }
    private List<Map<String,Object>> views(List<Map<String,Object>> rows){return rows.stream().map(this::view).toList();}
    private void event(String report,Long actor,String action,String detail){db.update("INSERT INTO moderation_event(id,report_id,actor_id,action,detail,created_at) VALUES (?,?,?,?,?,?)",UUID.randomUUID().toString(),report,actor,action,detail,now());}

    @Transactional
    public String report(String type,String source,String reason){
        var me=current.requireCurrent();reason=text(reason,500);
        if(!Set.of("MESSAGE","CHAT").contains(type)||source==null||!source.matches("[a-zA-Z0-9-]{1,36}"))throw bad("举报对象无效");
        if(type.equals("CHAT"))try{long chatId=Long.parseLong(source);if(chatId<1)throw bad("消息编号无效");source=Long.toString(chatId);}catch(NumberFormatException e){throw bad("消息编号无效");}
        Map<String,Object> evidence;
        if(type.equals("MESSAGE")){
            evidence=row("internal_message",source,false);
            Object workspace=evidence.get("workspace_id");
            if(workspace!=null)groups.requireContentPermission(((Number)workspace).longValue(),false);
            else if(number(evidence,"sender_id")!=me.getId() && db.queryForObject("SELECT COUNT(*) FROM message_recipient WHERE message_id=? AND recipient_id=?",Integer.class,source,me.getId())==0)throw denied();
        }else{
            evidence=row("chat_message",source,false);
            var contact=db.queryForMap("SELECT low_user_id,high_user_id FROM contact_link WHERE id=?",evidence.get("contact_id"));
            if(number(contact,"low_user_id")!=me.getId() && number(contact,"high_user_id")!=me.getId())throw denied();
        }
        long target=number(evidence,"sender_id");
        if(target==me.getId())throw bad("不能举报自己发送的消息");
        // Serialize per reporter so concurrent duplicate/rate-limited requests cannot bypass limits.
        users.findLockedById(Math.min(me.getId(),target)).orElseThrow();
        users.findLockedById(Math.max(me.getId(),target)).orElseThrow();
        if(db.queryForObject("SELECT COUNT(*) FROM moderation_report WHERE source_type=? AND source_id=? AND reporter_id=?",Integer.class,type,source,me.getId())>0)throw new BusinessException(HttpStatus.CONFLICT,"REPORT_EXISTS","这条消息已经举报过，可在举报记录查看进度");
        if(db.queryForObject("SELECT COUNT(*) FROM moderation_report WHERE reporter_id=? AND created_at>?",Integer.class,me.getId(),Timestamp.from(Instant.now().minusSeconds(86400)))>=30)throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,"REPORT_LIMIT","今天提交较多，请等待已有举报审核");
        var targetUser=users.findById(target).orElseThrow();
        boolean firstReport=db.queryForObject("SELECT COUNT(*) FROM moderation_report WHERE source_type=? AND source_id=?",Integer.class,type,source)==0;
        String body=(String)evidence.get("body");
        // Match only an entire direct threat; quotations, substrings and ordinary insults need human review.
        boolean urgent=body.trim().matches("(?i)(我要杀了你|我要弄死你|i will kill you)[!！.。\\s]*");
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO moderation_report(id,source_type,source_id,reporter_id,target_id,target_name,target_identity,evidence,reason,status,urgent,created_at) VALUES (?,?,?,?,?,?,?,?,?,'PENDING',?,?)",
                id,type,source,me.getId(),target,targetUser.getDisplayName(),targetUser.getIdentityCode(),body,reason,urgent,now());
        if(type.equals("MESSAGE") && evidence.get("workspace_id")!=null)
            db.update("INSERT INTO group_report(id,workspace_id,message_id,reporter_id,reason,status,created_at) VALUES (?,?,?,?,?,'OPEN',?)",id,evidence.get("workspace_id"),source,me.getId(),reason,now());
        event(id,me.getId(),"REPORT","提交举报");
        if(urgent && firstReport && !current.isSuperAdmin(targetUser)){
            if(db.queryForObject("SELECT COUNT(*) FROM moderation_penalty WHERE target_id=? AND automatic=TRUE AND revoked_at IS NULL AND expires_at>?",Integer.class,target,now())==0)
                penalty(id,target,null,"MUTE",Set.of(),"明确直接威胁，暂时禁言 30 分钟，等待人工复核",1800,true);
        }
        live.changed(List.of(target,me.getId()));return id;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> mine(){
        long me=current.requireCurrent().getId();
        return Map.of("reports",views(db.queryForList("SELECT id,source_type,source_id,target_name,target_identity,reason,status,urgent,review_reason,created_at,reviewed_at FROM moderation_report WHERE reporter_id=? ORDER BY created_at DESC LIMIT 100",me)),
                "penalties",views(db.queryForList("SELECT * FROM moderation_penalty WHERE target_id=? ORDER BY created_at DESC LIMIT 100",me)),
                "decisions",views(db.queryForList("SELECT id,status,review_reason,reviewed_at FROM moderation_report WHERE target_id=? AND status='RESOLVED' ORDER BY reviewed_at DESC LIMIT 100",me)));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> queue(String status,int page){
        reviewer();if(!Set.of("PENDING","RESOLVED","DISMISSED","ALL","APPEALS").contains(status)||page<0||page>10000)throw bad("筛选或页码无效");
        String where=status.equals("ALL")?"":status.equals("APPEALS")?" WHERE EXISTS (SELECT 1 FROM moderation_penalty p WHERE p.report_id=moderation_report.id AND p.appeal_at IS NOT NULL AND p.appeal_reply IS NULL AND p.revoked_at IS NULL)":" WHERE status=?";
        List<Object> args=new ArrayList<>();if(!Set.of("ALL","APPEALS").contains(status))args.add(status);
        long total=db.queryForObject("SELECT COUNT(*) FROM moderation_report"+where,Long.class,args.toArray());
        args.add(page*25);
        var rows=db.queryForList("SELECT id,source_type,target_name,target_identity,reason,status,urgent,created_at,reviewed_at FROM moderation_report"+where+" ORDER BY urgent DESC,created_at DESC LIMIT 25 OFFSET ?",args.toArray());
        return Map.of("items",views(rows),"total",total,"page",page,"features",ModerationGuard.FEATURES);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> detail(String id){
        reviewer();var r=row("moderation_report",id,false);
        return Map.of("report",view(r),"penalties",views(db.queryForList("SELECT * FROM moderation_penalty WHERE report_id=? ORDER BY created_at DESC",id)),
                "events",views(db.queryForList("SELECT e.*,u.display_name AS actor_name FROM moderation_event e LEFT JOIN app_user u ON u.id=e.actor_id WHERE report_id=? ORDER BY created_at",id)));
    }

    @Transactional
    public void review(String id,String decision,String reason,Integer minutes,Set<String> features){
        roles.lockAdminGuard();
        var actor=reviewer();reason=text(reason,1000);
        if(!Set.of("DISMISS","WARNING","MUTE","FEATURE","BAN").contains(decision))throw bad("请选择有效处理方式");
        var r=row("moderation_report",id,true);if(!r.get("status").equals("PENDING"))throw new BusinessException(HttpStatus.CONFLICT,"REPORT_REVIEWED","此举报已由其他审核员处理，请刷新");
        long target=number(r,"target_id");
        if(target==actor.getId())throw bad("不能审核针对自己的举报，请交给另一位管理员");
        var u=users.findLockedById(target).orElseThrow();
        if(Set.of("MUTE","FEATURE","BAN").contains(decision)){
            if(minutes==null||minutes<1||minutes>525600)throw bad("处罚时间须为 1 分钟至 365 天");
            if(current.isSuperAdmin(u)&&!current.isSuperAdmin(actor))throw denied();
            if(decision.equals("BAN") && current.isSuperAdmin(u)){
                int other=db.queryForObject("SELECT COUNT(*) FROM app_user u JOIN app_role r ON r.id=u.role_id WHERE r.code='ADMIN' AND u.id<>? AND u.status='ACTIVE' AND (u.access_expires_at IS NULL OR u.access_expires_at>?) AND NOT EXISTS(SELECT 1 FROM moderation_penalty p WHERE p.target_id=u.id AND p.kind='BAN' AND p.revoked_at IS NULL AND p.expires_at>?)",Integer.class,target,now(),now());
                if(other==0)throw bad("不能封禁最后一个可用管理员");
            }
            Set<String> selected=features==null?Set.of():features;
            if(decision.equals("FEATURE") && (selected.isEmpty()||!ModerationGuard.FEATURES.keySet().containsAll(selected)))throw bad("请选择允许限制的功能");
            revokeAutomatic(id,actor.getId(),"人工复核替换临时处理");
            penalty(id,target,actor.getId(),decision,decision.equals("FEATURE")?selected:Set.of(),reason,minutes.longValue()*60,false);
        }else revokeAutomatic(id,actor.getId(),"人工复核解除临时处理");
        String status=decision.equals("DISMISS")?"DISMISSED":"RESOLVED";
        db.update("UPDATE moderation_report SET status=?,review_reason=?,reviewer_id=?,reviewed_at=? WHERE id=?",status,reason,actor.getId(),now(),id);
        db.update("UPDATE group_report SET status=? WHERE id=?",status,id);
        event(id,actor.getId(),"REVIEW",decision+"；"+reason);
        live.changed(List.of(target,number(r,"reporter_id")));
    }
    private void penalty(String report,long target,Long actor,String kind,Set<String> features,String reason,long seconds,boolean auto){
        db.update("INSERT INTO moderation_penalty(id,report_id,target_id,actor_id,kind,features,reason,automatic,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),report,target,actor,kind,String.join(",",new TreeSet<>(features)),reason,auto,now(),Timestamp.from(Instant.now().plusSeconds(seconds)));
        event(report,actor,auto?"AUTO_MUTE":"PENALTY",kind+"；"+seconds+" 秒；"+String.join(",",features)+"；"+reason);
    }
    private void revokeAutomatic(String report,long actor,String reason){
        if(db.update("UPDATE moderation_penalty SET revoked_at=?,appeal_reply=CASE WHEN appeal_at IS NOT NULL AND appeal_reply IS NULL THEN ? ELSE appeal_reply END WHERE report_id=? AND automatic=TRUE AND revoked_at IS NULL",now(),reason,report)>0)event(report,actor,"REVOKE_AUTO",reason);
    }
    @Transactional
    public void revoke(String id,String reason){
        roles.lockAdminGuard();
        var actor=reviewer();reason=text(reason,1000);var initial=row("moderation_penalty",id,false);
        row("moderation_report",(String)initial.get("report_id"),true);var p=row("moderation_penalty",id,true);
        if(number(p,"target_id")==actor.getId())throw bad("不能解除自己的处罚，请交给另一位管理员");
        var target=users.findById(number(p,"target_id")).orElseThrow();if(current.isSuperAdmin(target)&&!current.isSuperAdmin(actor))throw denied();
        if(p.get("revoked_at")!=null)throw bad("处罚已经解除");
        db.update("UPDATE moderation_penalty SET revoked_at=?,appeal_reply=? WHERE id=?",now(),reason,id);
        event((String)p.get("report_id"),actor.getId(),"REVOKE",id+"；"+reason);live.changed(List.of(number(p,"target_id")));
    }
    @Transactional
    public void appeal(String id,String reason){
        var me=current.requireCurrent();reason=text(reason,1000);var p=row("moderation_penalty",id,true);
        if(number(p,"target_id")!=me.getId())throw denied();
        if(p.get("appeal_at")!=null)throw bad("已经提交申诉，请等待审核");
        db.update("UPDATE moderation_penalty SET appeal=?,appeal_at=? WHERE id=?",reason,now(),id);
        event((String)p.get("report_id"),me.getId(),"APPEAL",reason);
    }
    @Transactional
    public void appealDecision(String id,boolean accept,String reason){
        roles.lockAdminGuard();
        var pending=row("moderation_penalty",id,false);
        if(pending.get("appeal_at")==null||pending.get("appeal_reply")!=null)throw bad("此申诉不能处理");
        if(accept){revoke(id,reason);return;}
        var actor=reviewer();reason=text(reason,1000);var initial=row("moderation_penalty",id,false);
        row("moderation_report",(String)initial.get("report_id"),true);var p=row("moderation_penalty",id,true);
        if(number(p,"target_id")==actor.getId()||p.get("appeal_at")==null||p.get("appeal_reply")!=null)throw bad("此申诉不能处理");
        db.update("UPDATE moderation_penalty SET appeal_reply=? WHERE id=?",reason,id);
        event((String)p.get("report_id"),actor.getId(),"APPEAL_REJECT",reason);live.changed(List.of(number(p,"target_id")));
    }
}
