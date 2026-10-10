package com.robustvision.platform.service;
import com.robustvision.platform.domain.NoteEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;
@Service
public class NoteLinkService {
 private final JdbcTemplate db; public NoteLinkService(JdbcTemplate db){this.db=db;}
 private static final Pattern LINK=Pattern.compile("/app/notes/([a-fA-F0-9-]{36})/edit(?:[\\s)\"#?]|$)");
 public void sync(NoteEntity note){
  long owner=note.getOwner().getId();db.update("DELETE FROM note_link WHERE source_id=? AND owner_id=?",note.getId(),owner);
  var matcher=LINK.matcher(note.getBody());Set<String> targets=new LinkedHashSet<>();while(matcher.find()&&targets.size()<100)targets.add(matcher.group(1).toLowerCase(Locale.ROOT));
  targets.remove(note.getId());if(targets.isEmpty())return;
  var params=new ArrayList<Object>();params.add(owner);params.addAll(targets);
  var owned=db.queryForList("SELECT id FROM note WHERE owner_id=? AND deleted_at IS NULL AND id IN ("+String.join(",",Collections.nCopies(targets.size(),"?"))+")",String.class,params.toArray());
  db.batchUpdate("INSERT INTO note_link(id,owner_id,source_id,target_id) VALUES (?,?,?,?)",owned.stream().map(target->new Object[]{UUID.randomUUID().toString(),owner,note.getId(),target}).toList());
 }
 public record Backlink(String id,String title){}
 public List<Backlink> backlinks(long owner,String target){
  var result=new LinkedHashMap<String,Backlink>();
  db.query("SELECT n.id,n.title FROM note_link l JOIN note n ON n.id=l.source_id WHERE l.owner_id=? AND l.target_id=? AND n.owner_id=? AND n.deleted_at IS NULL ORDER BY n.updated_at DESC LIMIT 100",(r,i)->new Backlink(r.getString(1),r.getString(2)),owner,target,owner).forEach(b->result.put(b.id(),b));
  // Old notes predate the index. Match only the current owner's potential sources;
  // a normal subsequent save replaces this compatibility lookup with indexed links.
  db.query("SELECT n.id,n.title,n.body FROM note n WHERE n.owner_id=? AND n.deleted_at IS NULL AND n.id<>? AND n.body LIKE ? AND NOT EXISTS (SELECT 1 FROM note_link l WHERE l.source_id=n.id AND l.target_id=?) ORDER BY n.updated_at DESC LIMIT 100",(org.springframework.jdbc.core.RowCallbackHandler)r->{var m=LINK.matcher(r.getString(3));while(m.find())if(m.group(1).equalsIgnoreCase(target)){result.put(r.getString(1),new Backlink(r.getString(1),r.getString(2)));break;}},owner,target,"%/app/notes/"+target+"/edit%",target);
  return result.values().stream().limit(100).toList();
 }
}
