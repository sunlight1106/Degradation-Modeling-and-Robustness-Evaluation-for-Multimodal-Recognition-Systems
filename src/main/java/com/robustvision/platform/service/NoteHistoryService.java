package com.robustvision.platform.service;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.repository.*;
import com.robustvision.platform.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;

@Service
public class NoteHistoryService {
    @org.springframework.beans.factory.annotation.Autowired private NoteLinkService links;
    private final NoteVersionRepository versions;
    private final NoteRepository notes;
    private final UserRepository users;
    private final CurrentUserService current;
    private final JdbcTemplate jdbc;
    public NoteHistoryService(NoteVersionRepository versions,NoteRepository notes,UserRepository users,CurrentUserService current,JdbcTemplate jdbc) {
        this.versions=versions; this.notes=notes; this.users=users; this.current=current; this.jdbc=jdbc;
    }
    public void capture(NoteEntity note) {
        if(!versions.existsByNoteIdAndRevision(note.getId(),note.getRevision())) versions.save(new NoteVersionEntity(note));
    }
    public record Summary(String id,long revision,String title,Instant createdAt) {}
    @Transactional(readOnly=true)
    public List<Summary> list(String id,int page) {
        long owner=current.requireCurrent().getId(); own(id,owner);
        return versions.findByOwnerIdAndNoteIdOrderByRevisionDesc(owner,id,PageRequest.of(Math.max(0,page),30)).stream()
                .map(v->new Summary(v.id,v.revision,v.title,v.createdAt)).toList();
    }
    @Transactional(readOnly=true)
    public NoteVersionEntity detail(String id,String version) {
        long owner=current.requireCurrent().getId(); own(id,owner);
        return versions.findByIdAndOwnerIdAndNoteId(version,owner,id).orElseThrow(this::missing);
    }
    @Transactional
    public void restoreVersion(String id,String version,long baseRevision) {
        long owner=current.requireCurrent().getId(); users.lockNoteOwner(owner);
        NoteEntity n=notes.lockOwned(id,owner).orElseThrow(this::missing);
        if(n.getRevision()!=baseRevision) throw new BusinessException(HttpStatus.CONFLICT,"NOTE_SYNC_CONFLICT","笔记已更新，请重新查看差异");
        NoteVersionEntity v=versions.findByIdAndOwnerIdAndNoteId(version,owner,id).orElseThrow(this::missing);
        capture(n); n.setTitle(v.title); n.setBody(v.body); n.setTags(v.tags); n.setLibrary(v.library);
        n.setContentFormat(v.contentFormat); n.setStatus(NoteStatus.valueOf(v.status)); n.incrementRevision(); n.setUpdatedAt(Instant.now());
        notes.flush(); links.sync(n);
    }
    public record TrashItem(String id,String title,Instant deletedAt) {}
    @Transactional(readOnly=true)
    public List<TrashItem> trash(int page) {
        return jdbc.query("SELECT id,title,deleted_at FROM note WHERE owner_id=? AND deleted_at IS NOT NULL ORDER BY deleted_at DESC,id LIMIT 30 OFFSET ?",
                (r,i)->new TrashItem(r.getString(1),r.getString(2),r.getTimestamp(3).toInstant()),current.requireCurrent().getId(),Math.max(0,page)*30);
    }
    @Transactional
    public void restoreDeleted(String id) {
        long owner=current.requireCurrent().getId(); users.lockNoteOwner(owner);
        if(jdbc.update("UPDATE note SET deleted_at=NULL,parent_id=NULL,revision=revision+1,updated_at=? WHERE id=? AND owner_id=? AND deleted_at IS NOT NULL",java.sql.Timestamp.from(Instant.now()),id,owner)!=1) throw missing();
    }
    @Transactional
    public void purge(String id) {
        long owner=current.requireCurrent().getId(); users.lockNoteOwner(owner);
        // Purge only explicitly selected, already trashed documents. No automatic expiry.
        if(jdbc.queryForObject("SELECT COUNT(*) FROM note WHERE id=? AND owner_id=? AND deleted_at IS NOT NULL",Long.class,id,owner)!=1) throw missing();
        jdbc.update("DELETE FROM note_reference WHERE note_id=?",id);
        jdbc.update("DELETE FROM note_share WHERE note_id=?",id);
        versions.deleteByNoteIdAndOwnerId(id,owner);
        jdbc.update("DELETE FROM note WHERE id=? AND owner_id=? AND deleted_at IS NOT NULL",id,owner);
    }
    private void own(String id,long owner) { if(notes.findByIdAndOwnerId(id,owner).isEmpty()) throw missing(); }
    private BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND,"NOTE_NOT_FOUND","笔记不存在或无权访问"); }
}
