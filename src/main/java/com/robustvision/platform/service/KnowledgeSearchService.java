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
    private static final Set<String> KINDS=Set.of("ALL","NOTE","ENTRY","FILE","TASK","RESULT","GROUP");
    public record Filters(String q,String type,String tag,Instant since,Long group,String sort) {}
    public static Filters validate(Filters input) {
        String q=input.q()==null?"":input.q().trim().replaceAll("\\s+"," ");
        String type=input.type()==null||input.type().isBlank()?"ALL":input.type().toUpperCase(Locale.ROOT);
        String tag=input.tag()==null?"":input.tag().trim();
        String sort=input.sort()==null||input.sort().isBlank()?"relevance":input.sort();
        if(q.length()>160||tag.length()>80||!KINDS.contains(type)||!Set.of("relevance","recent").contains(sort)
                ||(!q.isEmpty()&&q.split(" ").length>10)||input.group()!=null&&input.group()<1)
            throw new BusinessException(HttpStatus.BAD_REQUEST,"SEARCH_INVALID","搜索最多 10 个关键词，请检查资料类型、排序或群组编号");
        return new Filters(q,type,tag,input.since(),input.group(),sort);
    }
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
        if(group==null && accepts(type,"RESULT") && current.hasPermission(u,"personal-ai:use")) {
            sql.add("SELECT id,'RESULT' kind,file_name title,result_text body,created_at updated_at,'' tags FROM personal_recognition_result WHERE owner_id=?");args.add(owner);
        }
        if(group==null && accepts(type,"TASK") && (current.hasPermission(u,"experiment:read") || current.hasPermission(u,"experiment:read:any"))) {
            sql.add("SELECT t.id,'TASK' kind,f.original_name title,CONCAT(COALESCE(t.baseline_result,''),' ',COALESCE(t.optimized_result,'')) body,t.created_at updated_at,'' tags FROM inference_task t JOIN file_asset f ON f.id=t.input_file_id WHERE t.requested_by=? AND f.owner_id=?");args.add(owner);args.add(owner);
        }
        if(accepts(type,"GROUP") && current.hasPermission(u,"group:use") && current.hasPermission(u,"message:read")) {
            sql.add("SELECT m.id,'GROUP' kind,m.subject title,m.body,m.created_at updated_at,'' tags FROM internal_message m JOIN workspace_member wm ON wm.workspace_id=m.workspace_id WHERE wm.user_id=? AND EXISTS (SELECT 1 FROM workspace_member_permission p WHERE p.workspace_member_id=wm.id AND p.permission_code='CONTENT_READ')"+(group==null?"":" AND m.workspace_id=?"));args.add(owner);if(group!=null)args.add(group);
        }
        if(sql.isEmpty()) return new Scope("SELECT id,'NOTE' kind,title,body,updated_at,'' tags FROM note WHERE 1=0",List.of());
        return new Scope(String.join(" UNION ALL ",sql),args);
    }
    private boolean accepts(String t,String value){return t==null||t.isBlank()||t.equals("ALL")||t.equals(value);}
    private String like(String s){return "%"+s.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    @Transactional(readOnly=true)
    public Page search(String query,String type,String tag,Instant since,Long group,int page) {
        return search(new Filters(query,type,tag,since,group,"relevance"),page);
    }
    @Transactional(readOnly=true)
    public Page search(Filters input,int page) {
        Filters f=validate(input);validPage(page);Scope s=scope(f.type(),f.group());
        List<String> terms=f.q().isEmpty()?List.of():Arrays.stream(f.q().toLowerCase(Locale.ROOT).split(" ")).distinct().toList();
        List<Object> args=new ArrayList<>();String score="0";
        if(!terms.isEmpty()&&f.sort().equals("relevance")) {
            score="CASE WHEN LOWER(title)=? THEN 100 ELSE 0 END + CASE WHEN LOWER(title) LIKE ? ESCAPE '!' THEN 40 ELSE 0 END";
            args.add(f.q().toLowerCase(Locale.ROOT));args.add(like(f.q()));
            for(String term:terms){score+=" + CASE WHEN LOWER(title) LIKE ? ESCAPE '!' THEN 20 ELSE 0 END + CASE WHEN LOWER(tags) LIKE ? ESCAPE '!' THEN 8 ELSE 0 END";args.add(like(term));args.add(like(term));}
        }
        // Only the bounded excerpt crosses JDBC; full LONGTEXT bodies stay in MySQL.
        String sql="SELECT id,kind,title,updated_at,"+score+" score,SUBSTRING(COALESCE(body,''),GREATEST(1,LOCATE(?,LOWER(COALESCE(body,'')))-60),240) excerpt FROM ("+s.sql()+") scoped WHERE 1=1";
        args.add(terms.isEmpty()?"":terms.get(0));args.addAll(s.args());
        for(String term:terms){sql+=" AND (LOWER(title) LIKE ? ESCAPE '!' OR LOWER(body) LIKE ? ESCAPE '!' OR LOWER(tags) LIKE ? ESCAPE '!')";args.add(like(term));args.add(like(term));args.add(like(term));}
        if(!f.tag().isBlank()){sql+=" AND LOWER(tags) LIKE ? ESCAPE '!'";args.add(like(f.tag()));}
        if(f.since()!=null){sql+=" AND updated_at>=?";args.add(java.sql.Timestamp.from(f.since()));}
        sql+=" ORDER BY score DESC,updated_at DESC,kind,id LIMIT 31 OFFSET ?";args.add(page*30);
        List<Hit> hits=jdbc.query(sql,(r,i)->hit(r),args.toArray());
        return new Page(hits.stream().limit(30).toList(),hits.size()>30,page);
    }
    @Transactional(readOnly=true)
    public Page bookmarks(int page) {
        validPage(page);Scope s=scope("ALL",null);List<Object> args=new ArrayList<>(s.args());args.add(current.requireCurrent().getId());args.add(page*30);
        List<Hit> rows=jdbc.query("SELECT scoped.id,scoped.kind,scoped.title,scoped.updated_at,SUBSTRING(COALESCE(scoped.body,''),1,240) excerpt FROM ("+s.sql()+") scoped JOIN workspace_shortcut b ON b.owner_id=? AND b.kind='BOOKMARK' AND b.source_kind=scoped.kind AND b.source_id=scoped.id ORDER BY b.created_at DESC,b.id LIMIT 31 OFFSET ?",(r,i)->hit(r),args.toArray());
        return new Page(rows.stream().limit(30).toList(),rows.size()>30,page);
    }
    @Transactional(readOnly=true)
    public Set<String> accessibleBookmarkKeys() {
        Scope s=scope("ALL",null);List<Object> args=new ArrayList<>(s.args());args.add(current.requireCurrent().getId());
        return new HashSet<>(jdbc.queryForList("SELECT b.resource_key FROM ("+s.sql()+") scoped JOIN workspace_shortcut b ON b.owner_id=? AND b.kind='BOOKMARK' AND b.source_kind=scoped.kind AND b.source_id=scoped.id",String.class,args.toArray()));
    }
    private Hit hit(java.sql.ResultSet r) throws java.sql.SQLException {return new Hit(r.getString("id"),r.getString("kind"),r.getString("title"),plain(r.getString("excerpt")),url(r.getString("kind"),r.getString("id")),r.getTimestamp("updated_at").toInstant());}
    private void validPage(int page){if(page<0||page>10000)throw new BusinessException(HttpStatus.BAD_REQUEST,"SEARCH_INVALID","页码无效");}
    @Transactional(readOnly=true)
    public void requireSource(String kind,String id) {
        if(!KINDS.contains(kind)||kind.equals("ALL"))throw missing();
        Scope s=scope(kind,null);List<Object> args=new ArrayList<>(s.args());args.add(id);
        if(jdbc.queryForList("SELECT id FROM ("+s.sql()+") scoped WHERE id=? LIMIT 1",String.class,args.toArray()).isEmpty())throw missing();
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
