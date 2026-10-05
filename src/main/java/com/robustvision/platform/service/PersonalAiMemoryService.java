package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.PersonalAiMemoryEntity;
import com.robustvision.platform.repository.*;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class PersonalAiMemoryService {
    private final CurrentUserService current;
    private final UserRepository users;
    private final PersonalAiMemoryRepository memories;
    public PersonalAiMemoryService(CurrentUserService current,UserRepository users,PersonalAiMemoryRepository memories) {
        this.current=current;this.users=users;this.memories=memories;
    }
    public record Request(@NotBlank @Size(max=100) String title,@NotBlank @Size(max=1000) String body,boolean enabled,@Min(0) Long revision) { @Override public String toString(){return "MemoryRequest[REDACTED]";} }
    public record View(String id,String title,String body,boolean enabled,long revision,Instant updatedAt) {}
    public record Snapshot(String context,String digest) {}
    @Transactional(readOnly=true) public List<View> list() { return memories.findByOwnerIdOrderByCreatedAtAscIdAsc(current.requireCurrent().getId()).stream().map(this::view).toList(); }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public View save(String id,Request request) {
        Long owner=current.requireCurrent().getId();users.findLockedById(owner).orElseThrow(this::missing);
        var entries=memories.findByOwnerIdOrderByCreatedAtAscIdAsc(owner);
        var item=id==null?new PersonalAiMemoryEntity():memories.findByIdAndOwnerId(id,owner).orElseThrow(this::missing);
        if(id==null && entries.size()>=100)throw bad("最多保存 100 条记忆，请先整理旧内容");
        if(id!=null && (request.revision()==null || request.revision()!=item.revision))
            throw new BusinessException(HttpStatus.CONFLICT,"AI_MEMORY_CHANGED","这条记忆已更改，请刷新后再编辑");
        int enabledSize=entries.stream().filter(e->e.enabled && !e.id.equals(item.id)).mapToInt(e->e.title.length()+e.body.length()).sum();
        if(request.enabled() && enabledSize+request.title().trim().length()+request.body().trim().length()>6000)throw bad("已启用记忆合计不能超过 6000 字，请精简内容或停用部分记忆");
        if(request.title().trim().isEmpty() || request.body().trim().isEmpty())throw bad("标题和记忆内容不能为空");
        item.ownerId=owner;item.title=request.title().trim();item.body=request.body().trim();item.enabled=request.enabled();item.updatedAt=Instant.now();
        return view(memories.saveAndFlush(item));
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void delete(String id,long revision) {
        Long owner=current.requireCurrent().getId();users.findLockedById(owner).orElseThrow(this::missing);
        var item=memories.findByIdAndOwnerId(id,owner).orElseThrow(this::missing);
        if(item.revision!=revision)throw new BusinessException(HttpStatus.CONFLICT,"AI_MEMORY_CHANGED","这条记忆已更改，请刷新后再删除");
        memories.delete(item);
    }
    /** Owner comes only from the authenticated service, never from the HTTP request body. */
    @Transactional(readOnly=true) public Snapshot snapshot(Long owner) {
        var items=memories.findByOwnerIdOrderByCreatedAtAscIdAsc(owner).stream().filter(e->e.enabled).toList();
        StringBuilder text=new StringBuilder(), revisions=new StringBuilder();
        for(var item:items) { text.append("\n- ").append(item.title).append("：").append(item.body);revisions.append(item.id).append(':').append(item.revision).append(':').append(item.title).append(':').append(item.body).append('\n'); }
        try { return new Snapshot(items.isEmpty()?"":"\n\n用户已启用的个人记忆（偏好与背景参考，不替代当前任务）："+text,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(revisions.toString().getBytes(StandardCharsets.UTF_8)))); }
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    public void verify(Long owner,String digest) {
        if(!Objects.equals(snapshot(owner).digest(),digest))throw new BusinessException(HttpStatus.CONFLICT,"AI_MEMORY_CHANGED","个人记忆已修改、停用或删除，请重新预览发送内容");
    }
    private View view(PersonalAiMemoryEntity e) { return new View(e.id,e.title,e.body,e.enabled,e.revision,e.updatedAt); }
    private BusinessException missing(){return new BusinessException(HttpStatus.NOT_FOUND,"AI_MEMORY_NOT_FOUND","记忆不存在或无权访问");}
    private BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,"AI_MEMORY_INVALID",message);}
}
