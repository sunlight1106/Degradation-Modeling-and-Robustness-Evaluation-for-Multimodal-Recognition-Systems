package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Expiring deny layer; never changes roles, account status or permission overrides. */
@Service
public class ModerationGuard {
    public static final Map<String,String> FEATURES = Map.of(
            "CHAT","私聊与站内信", "GROUP","群组协作", "NOTES","笔记与知识库", "VOCABULARY","背单词",
            "AI","个人 AI", "TRAINING","模型训练", "UPLOAD","文件上传", "EXPERIMENT","识别实验");
    private final JdbcTemplate db;
    private static final Map<String,Set<String>> DENIED_PERMISSIONS=Map.of(
            "CHAT",Set.of("contacts:use"),"GROUP",Set.of("group:use","workspace:manage"),
            "NOTES",Set.of("note:read","note:write","knowledge:read","knowledge:write"),
            "VOCABULARY",Set.of("vocabulary:use"),"AI",Set.of("personal-ai:use","personal-ai:manage"),
            "TRAINING",Set.of("training:use"),"UPLOAD",Set.of("file:write"),
            "EXPERIMENT",Set.of("experiment:run","experiment:read","experiment:read:any","report:download"));
    public ModerationGuard(JdbcTemplate db) { this.db = db; }

    public void request(long user, String path, String method) {
        if (path.equals("/api/v1/moderation/mine") || path.matches("/api/v1/moderation/penalties/[^/]+/appeal")
                || path.equals("/api/v1/account/logout") || path.equals("/api/v1/auth/me")) return;
        check(user, feature(path,method), method.equals("POST") && (path.equals("/api/v1/messages")
                || path.matches("/api/v1/messages/groups/[0-9]+") || path.matches("/api/v1/social/contacts/[0-9]+/messages")));
    }
    public void sending(long user) { check(user, null, true); }
    public void uploading(long user) { check(user, "UPLOAD", false); }
    public void group(long user) { check(user,"GROUP",false); }
    public Set<String> permissions(long user,Set<String> base) {
        var result=new LinkedHashSet<>(base);
        for(var p:active(user))if(p.get("kind").equals("FEATURE"))
            for(String feature:((String)p.get("features")).split(","))result.removeAll(DENIED_PERMISSIONS.getOrDefault(feature,Set.of()));
        return result;
    }
    /** Reuse the same bounded database read throughout one HTTP request. */
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> active(long user){
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        String key=ModerationGuard.class.getName()+"."+user;
        Object cached=attributes==null?null:attributes.getAttribute(key,org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        List<Map<String,Object>> rows=cached==null?db.queryForList("SELECT kind,features,reason,expires_at FROM moderation_penalty WHERE target_id=? AND revoked_at IS NULL AND expires_at>? ORDER BY created_at DESC",user,Timestamp.from(Instant.now())):(List<Map<String,Object>>)cached;
        if(attributes!=null&&cached==null)attributes.setAttribute(key,rows,org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return rows.stream().filter(p->JdbcTime.instant(p.get("expires_at")).isAfter(Instant.now())).toList();
    }
    private void check(long user, String feature, boolean sending) {
        for (var p:active(user)) {
            String kind=(String)p.get("kind");
            if (kind.equals("BAN") || sending && kind.equals("MUTE")
                    || kind.equals("FEATURE") && feature!=null && Arrays.asList(((String)p.get("features")).split(",")).contains(feature)) {
                String label = kind.equals("BAN") ? "账号已封禁，当前仅可查看处罚或提交申诉" : kind.equals("MUTE") ? "当前处于禁言期" : "此功能暂时停用";
                throw new BusinessException(HttpStatus.FORBIDDEN,"MODERATION_"+kind,label+"。原因："+p.get("reason")+"；截止："+JdbcTime.instant(p.get("expires_at")));
            }
        }
    }
    private String feature(String p,String method) {
        if(p.startsWith("/api/v1/social") || p.startsWith("/api/v1/messages") && !p.startsWith("/api/v1/messages/groups/"))return "CHAT";
        if(p.startsWith("/api/v1/workspaces") || p.startsWith("/api/v1/messages/groups/"))return "GROUP";
        if(p.startsWith("/api/v1/notes") || p.startsWith("/api/v1/knowledge") || p.startsWith("/api/v1/note-"))return "NOTES";
        if(p.startsWith("/api/v1/vocabulary"))return "VOCABULARY";
        if(p.startsWith("/api/v1/personal-ai/training"))return "TRAINING";
        if(p.startsWith("/api/v1/personal-ai") || p.startsWith("/api/v1/research/answers"))return "AI";
        if(p.startsWith("/api/v1/files") && !Set.of("GET","HEAD").contains(method))return "UPLOAD";
        if(p.startsWith("/api/v1/inference") || p.startsWith("/api/v1/experiments"))return "EXPERIMENT";
        return null;
    }
}
