package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.dto.ApiDtos;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;

@Service
public class GroupConversationService {
    private final JdbcTemplate jdbc;private final WorkspaceService groups;private final CurrentUserService current;private final LiveUpdateService live;
    public GroupConversationService(JdbcTemplate jdbc,WorkspaceService groups,CurrentUserService current,LiveUpdateService live){this.jdbc=jdbc;this.groups=groups;this.current=current;this.live=live;}
    public record Preferences(@NotNull Boolean pinned,@NotNull Boolean muted) {}
    public record Announcement(@Size(max=2000) @NotNull String text,@Min(0) long revision) {}
    public record Pin(@Size(max=36) String messageId,@Min(0) long revision) {}
    public record Read(@NotBlank @Size(max=36) String messageId) {}
    public record PinnedMessage(String id,String body,String senderName,Instant createdAt) {}
    public record Features(long groupId,String announcement,long revision,boolean pinned,boolean muted,long unread,PinnedMessage pinnedMessage) {}
    public record Overview(List<ApiDtos.WorkspaceView> groups,List<Features> features) {}
    public record Person(long id,String identityCode,String username,String displayName) {}
    public record Directory(List<Person> items,boolean hasMore,int page) {}
    private record Notice(String text,String message,long revision) {}
    @Transactional(readOnly=true)
    public Overview overview(){var visible=groups.list();return new Overview(visible,features(visible));}
    @Transactional(readOnly=true)
    public Features get(long id){return features(List.of(groups.detail(id))).get(0);}
    private List<Features> features(List<ApiDtos.WorkspaceView> visible){
        if(visible.isEmpty())return List.of();var user=current.requireCurrent();long owner=user.getId();boolean canRead=current.hasPermission(user,"message:read");Map<Long,Features> found=new HashMap<>();
        for(int offset=0;offset<visible.size();offset+=500){var batch=visible.subList(offset,Math.min(visible.size(),offset+500));List<Object> args=new ArrayList<>(List.of(owner,owner,owner));args.addAll(batch.stream().map(ApiDtos.WorkspaceView::id).toList());
            String sql="SELECT w.id,COALESCE(n.announcement,'') announcement,COALESCE(n.revision,0) revision,COALESCE(p.pinned,FALSE) pinned,COALESCE(p.muted,FALSE) muted,n.pinned_message_id,pm.body pinned_body,u.display_name pinned_sender,pm.created_at pinned_time,(SELECT COUNT(*) FROM internal_message m WHERE m.workspace_id=w.id AND wm.id IS NOT NULL AND m.sender_id<>? AND (m.created_at>CASE WHEN p.read_through>wm.created_at THEN p.read_through ELSE COALESCE(wm.created_at,CURRENT_TIMESTAMP) END OR (p.read_through>=wm.created_at AND m.created_at=p.read_through AND m.id>p.read_message_id))) unread FROM workspace w LEFT JOIN workspace_notice n ON n.workspace_id=w.id LEFT JOIN workspace_preference p ON p.workspace_id=w.id AND p.owner_id=? LEFT JOIN workspace_member wm ON wm.workspace_id=w.id AND wm.user_id=p.owner_id LEFT JOIN internal_message pm ON pm.id=n.pinned_message_id AND pm.workspace_id=w.id LEFT JOIN app_user u ON u.id=pm.sender_id WHERE w.id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")";
            // Membership baseline must also work before the first preference row is created.
            sql=sql.replace("wm.user_id=p.owner_id","wm.user_id=?");
            jdbc.query(sql,r->{long id=r.getLong("id");String message=r.getString("pinned_message_id");PinnedMessage pin=message==null||r.getTimestamp("pinned_time")==null?null:new PinnedMessage(message,r.getString("pinned_body"),r.getString("pinned_sender"),r.getTimestamp("pinned_time").toInstant());found.put(id,new Features(id,r.getString("announcement"),r.getLong("revision"),r.getBoolean("pinned"),r.getBoolean("muted"),r.getLong("unread"),pin));},args.toArray());
        }
        return visible.stream().map(g->{Features f=found.get(g.id());if(f==null)throw new IllegalStateException("Group metadata missing");return canRead&&g.currentPermissions().contains("CONTENT_READ")?f:new Features(g.id(),"",f.revision(),f.pinned(),f.muted(),0,null);}).toList();
    }
    @Transactional
    public Features preferences(long id,Preferences input){groups.detail(id);long owner=lockOwner();ensurePreference(id,owner);jdbc.update("UPDATE workspace_preference SET pinned=?,muted=? WHERE workspace_id=? AND owner_id=?",input.pinned(),input.muted(),id,owner);live.changed(List.of(owner));return get(id);}
    @Transactional
    public Features read(long id,Read input){groups.requireContentPermission(id,false);long owner=lockOwner();var message=jdbc.query("SELECT created_at FROM internal_message WHERE id=? AND workspace_id=?",(r,i)->r.getTimestamp(1),input.messageId(),id).stream().findFirst().orElseThrow(this::missing);ensurePreference(id,owner);
        int changed=jdbc.update("UPDATE workspace_preference SET read_through=?,read_message_id=? WHERE workspace_id=? AND owner_id=? AND (read_through IS NULL OR read_through<? OR (read_through=? AND read_message_id<?))",message,input.messageId(),id,owner,message,message,input.messageId());if(changed>0)live.changed(List.of(owner));return get(id);}
    @Transactional
    public Features announcement(long id,Announcement input){groups.requireSettingsPermission(id);Notice notice=lockedNotice(id);checkRevision(notice,input.revision());saveNotice(id,input.text().trim(),notice.message(),notice.revision()+1);return get(id);}
    @Transactional
    public Features pin(long id,Pin input){groups.requireSettingsPermission(id);Notice notice=lockedNotice(id);checkRevision(notice,input.revision());if(input.messageId()!=null&&!input.messageId().isBlank()&&jdbc.queryForList("SELECT id FROM internal_message WHERE id=? AND workspace_id=?",String.class,input.messageId(),id).isEmpty())throw missing();saveNotice(id,notice.text(),input.messageId()==null||input.messageId().isBlank()?null:input.messageId(),notice.revision()+1);return get(id);}
    @Transactional(readOnly=true)
    public Directory directory(long id,String query,int page){var group=groups.detail(id);if(!group.currentPermissions().contains("MEMBERS_WRITE"))throw denied();if(query.length()>100||page<0||page>10000)throw new BusinessException(HttpStatus.BAD_REQUEST,"DIRECTORY_INVALID","搜索条件或页码无效");long owner=current.requireCurrent().getId();String q=query.trim(),like="%"+q.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        List<Person> rows=jdbc.query("SELECT u.id,u.identity_code,u.username,u.display_name FROM app_user u WHERE u.status='ACTIVE' AND (u.access_expires_at IS NULL OR u.access_expires_at>CURRENT_TIMESTAMP) AND u.id<>? AND NOT EXISTS (SELECT 1 FROM workspace_member m WHERE m.workspace_id=? AND m.user_id=u.id) AND (u.identity_code=? OR LOWER(u.display_name) LIKE ? ESCAPE '!' OR LOWER(u.username) LIKE ? ESCAPE '!') ORDER BY u.display_name,u.id LIMIT 26 OFFSET ?",(r,i)->new Person(r.getLong(1),r.getString(2),r.getString(3),r.getString(4)),owner,id,q.toUpperCase(Locale.ROOT),like,like,page*25);
        return new Directory(rows.stream().limit(25).toList(),rows.size()>25,page);}
    @Transactional(readOnly=true)
    public List<ApiDtos.MessageView> search(long id,String query,int page){groups.requireContentPermission(id,false);if(query.isBlank()||query.length()>100||page<0||page>10000)throw new BusinessException(HttpStatus.BAD_REQUEST,"GROUP_SEARCH_INVALID","请输入最多 100 字的关键词");String like="%"+query.trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        return jdbc.query("SELECT m.id,m.sender_id,u.display_name,m.subject,m.body,m.created_at,m.reply_to_id FROM internal_message m JOIN app_user u ON u.id=m.sender_id WHERE m.workspace_id=? AND LOWER(m.body) LIKE ? ESCAPE '!' ORDER BY m.created_at DESC,m.id DESC LIMIT 50 OFFSET ?",(r,i)->new ApiDtos.MessageView(r.getString(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),List.of(),List.of(),true,r.getTimestamp(6).toInstant(),id,r.getString(7)),id,like,page*50);}
    private long lockOwner(){long owner=current.requireCurrent().getId();jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",Long.class,owner);return owner;}
    private void ensurePreference(long id,long owner){if(jdbc.queryForList("SELECT id FROM workspace_preference WHERE workspace_id=? AND owner_id=? FOR UPDATE",String.class,id,owner).isEmpty())jdbc.update("INSERT INTO workspace_preference(id,workspace_id,owner_id,pinned,muted,read_message_id) VALUES (?,?,?,FALSE,FALSE,'')",UUID.randomUUID().toString(),id,owner);}
    private Notice lockedNotice(long id){return jdbc.query("SELECT announcement,pinned_message_id,revision FROM workspace_notice WHERE workspace_id=? FOR UPDATE",(r,i)->new Notice(r.getString(1),r.getString(2),r.getLong(3)),id).stream().findFirst().orElse(new Notice("",null,0));}
    private void saveNotice(long id,String text,String message,long revision){Timestamp now=Timestamp.from(Instant.now());if(revision==1)jdbc.update("INSERT INTO workspace_notice(workspace_id,announcement,pinned_message_id,revision,updated_at) VALUES (?,?,?,?,?)",id,text,message,revision,now);else jdbc.update("UPDATE workspace_notice SET announcement=?,pinned_message_id=?,revision=?,updated_at=? WHERE workspace_id=?",text,message,revision,now,id);live.changed(jdbc.queryForList("SELECT user_id FROM workspace_member WHERE workspace_id=?",Long.class,id));}
    private void checkRevision(Notice notice,long revision){if(notice.revision()!=revision)throw new BusinessException(HttpStatus.CONFLICT,"GROUP_NOTICE_CONFLICT","公告或置顶已在别处更新，请刷新后再修改");}
    private BusinessException missing(){return new BusinessException(HttpStatus.NOT_FOUND,"GROUP_MESSAGE_UNAVAILABLE","消息不存在或不属于当前群组");}
    private BusinessException denied(){return new BusinessException(HttpStatus.FORBIDDEN,"GROUP_PERMISSION_DENIED","没有本群成员管理权限");}
}
