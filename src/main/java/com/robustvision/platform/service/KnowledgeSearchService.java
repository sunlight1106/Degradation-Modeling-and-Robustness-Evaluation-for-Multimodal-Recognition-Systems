package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.UserEntity;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** A single permission-scoped query; private documents never inherit administrator access. */
@Service
public class KnowledgeSearchService {
    private final JdbcTemplate jdbc;
    private final CurrentUserService current;
    public KnowledgeSearchService(JdbcTemplate jdbc,CurrentUserService current){this.jdbc=jdbc;this.current=current;}
    public record Hit(String id,String kind,String title,String excerpt,String url,Instant updatedAt) {}
    public record Source(String id,String kind,String title,String body,String url,Instant updatedAt) {}
    public record Page(List<Hit> items,boolean hasMore,int page) {}
    private record Scope(String sql,List<Object> args) {}
    private Scope scope(String type,Long group) {
        UserEntity u=current.requireCurrent();long owner=u.getId(); List<String> sql=new ArrayList<>();List<Object> args=new ArrayList<>();
        if(group==null && accepts(type,"NOTE") && current.hasPermission(u,"note:read")) {
            sql.add("SELECT id,'NOTE' kind,title,body,updated_at,COALESCE(tags,'') tags FROM note WHERE owner_id=? AND deleted_at IS NULL");args.add(owner);
        }
        if(group==null && accepts(type,"ENTRY") && current.hasPermission(u,"knowledge:read")) {
            sql.add("SELECT e.id,'ENTRY' kind,e.title,e.body,e.updated_at,COALESCE(e.tags,'') tags FROM knowledge_entry e JOIN knowledge_topic t ON t.id=e.topic_id WHERE (e.owner_id IS NULL OR e.owner_id=?) AND (t.owner_id IS NULL OR t.owner_id=?)");args.add(owner);args.add(owner);
        }
        if(group==null && accepts(type,"FILE") && (current.hasPermission(u,"file:read") || current.hasPermission(u,"file:read:any"))) {
            sql.add("SELECT id,'FILE' kind,original_name title,original_name body,created_at updated_at,'' tags FROM file_asset WHERE owner_id=? AND scan_status='CLEAN'");args.add(owner);
        }
        if(group==null && accepts(type,"RESULT")) {
            sql.add("SELECT id,'RESULT' kind,file_name title,result_text body,created_at updated_at,'' tags FROM personal_recognition_result WHERE owner_id=?");args.add(owner);
        }
        if(group==null && accepts(type,"TASK") && (current.hasPermission(u,"experiment:read") || current.hasPermission(u,"experiment:read:any"))) {
            sql.add("SELECT t.id,'TASK' kind,f.original_name title,CONCAT(COALESCE(t.baseline_result,''),' ',COALESCE(t.optimized_result,'')) body,t.created_at updated_at,'' tags FROM inference_task t JOIN file_asset f ON f.id=t.input_file_id WHERE t.requested_by=? AND f.owner_id=?");args.add(owner);args.add(owner);
        }
        if(accepts(type,"GROUP")) {
            sql.add("SELECT m.id,'GROUP' kind,m.subject title,m.body,m.created_at updated_at,'' tags FROM internal_message m JOIN workspace_member wm ON wm.workspace_id=m.workspace_id WHERE wm.user_id=? AND EXISTS (SELECT 1 FROM workspace_member_permission p WHERE p.workspace_member_id=wm.id AND p.permission_code='CONTENT_READ')"+(group==null?"":" AND m.workspace_id=?"));args.add(owner);if(group!=null)args.add(group);
        }
        if(sql.isEmpty()) return new Scope("SELECT id,'NOTE' kind,title,body,updated_at,'' tags FROM note WHERE 1=0",List.of());
        return new Scope(String.join(" UNION ALL ",sql),args);
    }
    private boolean accepts(String t,String value){return t==null||t.isBlank()||t.equals("ALL")||t.equals(value);}
    private String like(String s){return "%"+s.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    @Transactional(readOnly=true)
    public Page search(String query,String type,String tag,Instant since,Long group,int page) {
        if(query.length()>160 || tag.length()>80 || page<0 || page>10000) throw new BusinessException(HttpStatus.BAD_REQUEST,"SEARCH_INVALID","搜索条件过长或页码无效");
        Scope s=scope(type,group);List<Object> args=new ArrayList<>(s.args());
        String sql="SELECT * FROM ("+s.sql()+") scoped WHERE (LOWER(title) LIKE ? ESCAPE '!' OR LOWER(body) LIKE ? ESCAPE '!' OR LOWER(tags) LIKE ? ESCAPE '!')";
        args.add(like(query.trim()));args.add(like(query.trim()));args.add(like(query.trim()));
        if(!tag.isBlank()){sql+=" AND LOWER(tags) LIKE ? ESCAPE '!'";args.add(like(tag.trim()));}
        if(since!=null){sql+=" AND updated_at>=?";args.add(java.sql.Timestamp.from(since));}
        sql+=" ORDER BY updated_at DESC,kind,id LIMIT 31 OFFSET ?";args.add(page*30);
        List<Hit> hits=jdbc.query(sql,(r,i)->{String body=plain(r.getString("body"));int pos=Math.max(0,body.toLowerCase(Locale.ROOT).indexOf(query.trim().toLowerCase(Locale.ROOT))-60);
            return new Hit(r.getString("id"),r.getString("kind"),r.getString("title"),body.substring(Math.min(pos,body.length()),Math.min(body.length(),pos+240)),url(r.getString("kind"),r.getString("id")),r.getTimestamp("updated_at").toInstant());},args.toArray());
        return new Page(hits.stream().limit(30).toList(),hits.size()>30,page);
    }
    @Transactional(readOnly=true)
    public Source source(String kind,String id) {
        if(!Set.of("NOTE","ENTRY","FILE","TASK","RESULT","GROUP").contains(kind)) throw missing();
        Scope s=scope(kind,null);List<Object> args=new ArrayList<>(s.args());args.add(id);
        return jdbc.query("SELECT * FROM ("+s.sql()+") scoped WHERE id=?",(r,i)->new Source(id,kind,r.getString("title"),plain(r.getString("body")),url(kind,id),r.getTimestamp("updated_at").toInstant()),args.toArray()).stream().findFirst().orElseThrow(this::missing);
    }
    private BusinessException missing(){return new BusinessException(HttpStatus.NOT_FOUND,"SOURCE_UNAVAILABLE","资料不存在或当前无权访问");}
    static String plain(String body){return body==null?"":body;}
    static String url(String kind,String id){return switch(kind){case "NOTE"->"/app/notes/"+id+"/edit";case "ENTRY"->"/app/knowledge?entry="+id;case "FILE"->"/app/images?file="+id;case "TASK"->"/app/logs?task="+id;case "RESULT"->"/app/research?tab=search&type=RESULT&source="+id;default->"/app/research?tab=search&type=GROUP&source="+id;};}
}
