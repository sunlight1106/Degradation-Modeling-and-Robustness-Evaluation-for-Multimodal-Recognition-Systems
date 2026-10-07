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
    private final CurrentUserService current;private final LiveUpdateService live;private final SocialService social;private final JdbcTemplate jdbc;
    public NotificationController(CurrentUserService current,LiveUpdateService live,SocialService social,JdbcTemplate jdbc){this.current=current;this.live=live;this.social=social;this.jdbc=jdbc;}
    @GetMapping(value="/events",produces="text/event-stream") public SseEmitter events(){return live.connect(current.requireCurrent().getId());}
    public record Item(String id,String title,String url,long count) {}
    @GetMapping public Object list(){
        long owner=current.requireCurrent().getId();List<Item> items=new ArrayList<>();
        for(var c:social.list()){
            if(c.available()&&!c.muted()&&c.unreadCount()>0)items.add(new Item("contact-"+c.id(),c.remark()==null||c.remark().isBlank()?c.displayName():c.remark(),"/app/contacts?contact="+c.id(),c.unreadCount()));
            if(c.available()&&c.incoming()&&c.status().equals("PENDING"))items.add(new Item("request-"+c.id(),c.displayName()+" 请求添加联系人","/app/contacts?tab=requests",1));
        }
        items.addAll(jdbc.query("SELECT m.id,m.subject FROM internal_message m JOIN message_recipient r ON r.message_id=m.id WHERE r.recipient_id=? AND r.read_at IS NULL AND m.workspace_id IS NULL ORDER BY m.created_at DESC LIMIT 30",(r,i)->new Item(r.getString(1),r.getString(2),"/app/mail?message="+r.getString(1),1),owner));
        return ApiResponse.ok(items);
    }
}
