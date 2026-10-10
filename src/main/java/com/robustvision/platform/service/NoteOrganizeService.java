package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class NoteOrganizeService {
    public record Selection(@NotBlank @Size(max=36) String id, @NotNull @Min(0) Long revision) {}
    public record Batch(@NotEmpty @Size(max=50) List<@NotNull @Valid Selection> notes, @NotBlank @Size(max=20) String action,
                        @Size(max=40) String library, @Size(max=500) String tags) {}
    private final NoteRepository notes; private final UserRepository users; private final CurrentUserService current;
    private final NoteHistoryService history; private final NoteShareRepository shares;
    public NoteOrganizeService(NoteRepository notes,UserRepository users,CurrentUserService current,NoteHistoryService history,NoteShareRepository shares) {
        this.notes=notes;this.users=users;this.current=current;this.history=history;this.shares=shares;
    }
    @Transactional
    public int apply(Batch input) {
        if (!Set.of("MOVE","ARCHIVE","DRAFT","ACTIVE","TAGS","TRASH").contains(input.action())) throw bad("NOTE_ACTION_INVALID","请选择有效的整理操作");
        if ("MOVE".equals(input.action()) && (input.library()==null || input.library().isBlank())) throw bad("NOTE_LIBRARY_REQUIRED","请输入学习库名称");
        if ("TAGS".equals(input.action()) && (input.tags()==null || input.tags().isBlank())) throw bad("NOTE_TAGS_REQUIRED","请输入需要添加的标签");
        long owner=current.requireCurrent().getId(); users.lockNoteOwner(owner);
        Map<String,Long> revisions=new HashMap<>();
        for(var selection:input.notes()) if(revisions.put(selection.id(),selection.revision())!=null) throw bad("NOTE_SELECTION_DUPLICATE","请勿重复选择同一笔记");
        var rows=notes.lockBatch(owner,revisions.keySet());
        if(rows.size()!=revisions.size()) throw new BusinessException(HttpStatus.NOT_FOUND,"NOTE_NOT_FOUND","选中笔记不存在或无权访问，未执行任何修改");
        if(rows.stream().anyMatch(n->n.getRevision()!=revisions.get(n.getId()))) throw new BusinessException(HttpStatus.CONFLICT,"NOTE_SYNC_CONFLICT","选中笔记已有更新，请刷新列表后重新选择；未执行任何修改");
        Instant now=Instant.now();
        for(var n:rows) {
            history.capture(n);
            switch(input.action()) {
                case "MOVE" -> n.setLibrary(input.library().trim());
                case "ARCHIVE","DRAFT","ACTIVE" -> n.setStatus("ARCHIVE".equals(input.action())?NoteStatus.ARCHIVED:NoteStatus.valueOf(input.action()));
                case "TAGS" -> {
                    var tags=new LinkedHashSet<>(KnowledgeService.splitTags(n.getTags())); tags.addAll(KnowledgeService.splitTags(input.tags()));
                    String joined=String.join(", ",tags); if(joined.length()>500)throw bad("NOTE_TAGS_TOO_LONG","合并后的标签超过 500 字，请减少标签"); n.setTags(joined);
                }
                case "TRASH" -> { notes.detachChildren(n.getId(),owner);shares.findByNoteIdOrderByCreatedAtDesc(n.getId()).forEach(s->s.revoke(now)); n.trash(); }
            }
            if(!"TRASH".equals(input.action()))n.incrementRevision(); n.setUpdatedAt(now);
        }
        notes.flush(); return rows.size();
    }
    private static BusinessException bad(String code,String message){return new BusinessException(HttpStatus.BAD_REQUEST,code,message);}
}
