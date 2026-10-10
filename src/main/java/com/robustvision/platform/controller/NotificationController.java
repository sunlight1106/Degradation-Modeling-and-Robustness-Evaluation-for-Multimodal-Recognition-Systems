package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;

@RestController @RequestMapping("/api/v1/notifications") @PreAuthorize("isAuthenticated()")
public class NotificationController {
    @org.springframework.beans.factory.annotation.Autowired private com.robustvision.platform.service.BackupStatusService backupStatus;
    @org.springframework.beans.factory.annotation.Autowired private PersonalWorkflowService workflows;
    private final CurrentUserService current;private final LiveUpdateService live;private final SocialService social;private final JdbcTemplate jdbc;private final GroupConversationService groups;
    public NotificationController(CurrentUserService current,LiveUpdateService live,SocialService social,JdbcTemplate jdbc,GroupConversationService groups){this.current=current;this.live=live;this.social=social;this.jdbc=jdbc;this.groups=groups;}
    @GetMapping(value="/events",produces="text/event-stream") public SseEmitter events(){return live.connect(current.requireCurrent().getId());}
    public record Item(String id,String title,String url,long count) {}
    @GetMapping public Object list(){
        var user=current.requireCurrent();long owner=user.getId();List<Item> items=new ArrayList<>();
        var preferences=workflows.get(owner);
        if(preferences.groups()&&current.hasPermission(user,"group:use")&&current.hasPermission(user,"message:read")) {
            var overview=groups.overview();Map<Long,String> names=new HashMap<>();overview.groups().forEach(g->names.put(g.id(),g.name()));
            overview.features().stream().filter(f->!f.muted()&&f.unread()>0).limit(30).forEach(f->items.add(new Item("group-"+f.groupId(),names.get(f.groupId()),"/app/groups?group="+f.groupId(),f.unread())));
        }
        if(preferences.contacts())for(var c:social.list()){
            if(c.available()&&!c.muted()&&c.unreadCount()>0)items.add(new Item("contact-"+c.id(),c.remark()==null||c.remark().isBlank()?c.displayName():c.remark(),"/app/contacts?contact="+c.id(),c.unreadCount()));
            if(c.available()&&c.incoming()&&c.status().equals("PENDING"))items.add(new Item("request-"+c.id(),c.displayName()+" 请求添加联系人","/app/contacts?tab=requests",1));
        }
        if(preferences.mail()&&current.hasPermission(user,"message:read"))items.addAll(jdbc.query("SELECT m.id,m.subject FROM internal_message m JOIN message_recipient r ON r.message_id=m.id WHERE r.recipient_id=? AND r.read_at IS NULL AND m.workspace_id IS NULL ORDER BY m.created_at DESC LIMIT 30",(r,i)->new Item(r.getString(1),r.getString(2),"/app/mail?message="+r.getString(1),1),owner));
        if(preferences.study()&&current.hasPermission(user,"note:read")){var due=workflows.due(owner);if(!due.isEmpty())items.add(new Item("note-reviews","笔记待复习","/app/notes",due.size()));}
        if(current.isSuperAdmin(user)){var backup=backupStatus.read();if(backup.containsKey("lastError")||backup.containsKey("warning"))items.add(new Item("backup-warning","备份需要处理","/app/learning",1));}
        if(preferences.groups()&&current.hasPermission(user,"group:use")) {long invitations=jdbc.queryForObject("SELECT COUNT(*) FROM group_invitation WHERE target_id=? AND kind='INVITE' AND status='PENDING' AND expires_at>CURRENT_TIMESTAMP",Long.class,owner);if(invitations>0)items.add(new Item("group-invites","待确认的群组邀请","/app/groups",invitations));}
        return ApiResponse.ok(items);
    }
}
