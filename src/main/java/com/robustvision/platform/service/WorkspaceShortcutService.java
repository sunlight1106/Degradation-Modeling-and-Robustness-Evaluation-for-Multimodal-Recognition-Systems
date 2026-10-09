package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class WorkspaceShortcutService {
    private final JdbcTemplate jdbc;
    private final CurrentUserService current;
    private final KnowledgeSearchService search;
    private final ObjectMapper mapper;
    public WorkspaceShortcutService(JdbcTemplate jdbc,CurrentUserService current,KnowledgeSearchService search,ObjectMapper mapper){this.jdbc=jdbc;this.current=current;this.search=search;this.mapper=mapper;}
    public record Reference(@NotBlank @Size(max=16) String kind,@NotBlank @Size(max=36) String id) {}
    public record SavedSearch(String id,String name,KnowledgeSearchService.Filters filters,Instant createdAt) {}
    public record SaveSearch(@NotBlank @Size(max=80) String name,@NotNull @Valid KnowledgeSearchService.Filters filters) {}
    public record BookmarkKey(String kind,String id) {}

    @Transactional(readOnly=true)
    public List<BookmarkKey> bookmarkKeys(){return jdbc.query("SELECT source_kind,source_id FROM workspace_shortcut WHERE owner_id=? AND kind='BOOKMARK' ORDER BY created_at DESC,id",(r,i)->new BookmarkKey(r.getString(1),r.getString(2)),current.requireCurrent().getId());}

    @Transactional
    public void bookmark(Reference input) {
        search.requireSource(input.kind(),input.id());long owner=lockOwner();
        String key=input.kind()+":"+input.id();
        if(!existing(owner,"BOOKMARK",key).isEmpty())return;
        limit(owner,"BOOKMARK",200);
        jdbc.update("INSERT INTO workspace_shortcut(id,owner_id,kind,title,resource_key,source_kind,source_id,created_at) VALUES (?,?,'BOOKMARK',?,?,?,?,?)",UUID.randomUUID().toString(),owner,"",key,input.kind(),input.id(),java.sql.Timestamp.from(Instant.now()));
    }
    @Transactional
    public void removeBookmark(String kind,String id){jdbc.update("DELETE FROM workspace_shortcut WHERE owner_id=? AND kind='BOOKMARK' AND resource_key=?",current.requireCurrent().getId(),kind+":"+id);}
    @Transactional
    public int cleanupBookmarks(){long owner=lockOwner();Set<String> accessible=search.accessibleBookmarkKeys();List<String> obsolete=jdbc.queryForList("SELECT resource_key FROM workspace_shortcut WHERE owner_id=? AND kind='BOOKMARK'",String.class,owner).stream().filter(key->!accessible.contains(key)).toList();if(obsolete.isEmpty())return 0;
        List<Object> args=new ArrayList<>();args.add(owner);args.addAll(obsolete);
        return jdbc.update("DELETE FROM workspace_shortcut WHERE owner_id=? AND kind='BOOKMARK' AND resource_key IN ("+String.join(",",Collections.nCopies(obsolete.size(),"?"))+")",args.toArray());}

    @Transactional(readOnly=true)
    public List<SavedSearch> savedSearches(){return jdbc.query("SELECT id,title,filters_json,created_at FROM workspace_shortcut WHERE owner_id=? AND kind='SEARCH' ORDER BY created_at DESC,id",(r,i)->new SavedSearch(r.getString("id"),r.getString("title"),read(r.getString("filters_json")),r.getTimestamp("created_at").toInstant()),current.requireCurrent().getId());}

    @Transactional
    public SavedSearch saveSearch(SaveSearch input) {
        var filters=KnowledgeSearchService.validate(input.filters());String payload=write(filters),key=hash(payload);long owner=lockOwner();
        List<String> found=existing(owner,"SEARCH",key);Instant now=Instant.now();String id=found.isEmpty()?UUID.randomUUID().toString():found.get(0);
        if(found.isEmpty()){limit(owner,"SEARCH",50);jdbc.update("INSERT INTO workspace_shortcut(id,owner_id,kind,title,resource_key,filters_json,created_at) VALUES (?,?,'SEARCH',?,?,?,?)",id,owner,input.name().trim(),key,payload,java.sql.Timestamp.from(now));}
        else jdbc.update("UPDATE workspace_shortcut SET title=?,created_at=? WHERE id=? AND owner_id=?",input.name().trim(),java.sql.Timestamp.from(now),id,owner);
        return new SavedSearch(id,input.name().trim(),filters,now);
    }
    @Transactional
    public void removeSearch(String id){jdbc.update("DELETE FROM workspace_shortcut WHERE owner_id=? AND kind='SEARCH' AND id=?",current.requireCurrent().getId(),id);}
    private long lockOwner(){long owner=current.requireCurrent().getId();jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",Long.class,owner);return owner;}
    // Locking reads see the latest committed rows even under MySQL REPEATABLE READ.
    // A plain SELECT would keep a snapshot taken before a concurrent writer released the owner lock.
    private List<String> existing(long owner,String kind,String key){return jdbc.queryForList("SELECT id FROM workspace_shortcut WHERE owner_id=? AND kind=? AND resource_key=? FOR UPDATE",String.class,owner,kind,key);}
    private void limit(long owner,String kind,int cap){int count=jdbc.queryForList("SELECT id FROM workspace_shortcut WHERE owner_id=? AND kind=? FOR UPDATE",String.class,owner,kind).size();if(count>=cap)throw new BusinessException(HttpStatus.CONFLICT,"SHORTCUT_LIMIT","资料收藏最多 200 项，常用搜索最多 50 项，请先移除不需要的项目");}
    private String write(KnowledgeSearchService.Filters filters){try{return mapper.writeValueAsString(filters);}catch(Exception e){throw new IllegalStateException(e);}}
    private KnowledgeSearchService.Filters read(String json){try{return mapper.readValue(json,KnowledgeSearchService.Filters.class);}catch(Exception e){throw new IllegalStateException("Stored search filters are invalid",e);}}
    private String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
