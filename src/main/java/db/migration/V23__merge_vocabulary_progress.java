package db.migration;

import com.robustvision.platform.service.VocabularyTermKey;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Retain real historical answers and consolidate only the same owner's exact headword. */
public class V23__merge_vocabulary_progress extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        backfill(context.getConnection());
        try(var statement=context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE vocabulary_progress MODIFY COLUMN term_key VARCHAR(80) NOT NULL");
            statement.execute("CREATE UNIQUE INDEX uq_vocabulary_owner_term ON vocabulary_progress(owner_id,term_key)");
            statement.execute("CREATE INDEX idx_vocabulary_word_key ON vocabulary_word(book_id,term_key)");
        }
    }
    /** Also used to prepare legacy SQL fixtures on H2 without running MySQL DDL. */
    public static void backfill(Connection connection) throws Exception {
        Map<String,String> keys=new HashMap<>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id,term FROM vocabulary_word")) {
            while(rows.next()) {
                String id=rows.getString(1), key=VocabularyTermKey.of(rows.getString(2)); keys.put(id,key);
            }
        }
        // One bounded CASE update per 500 words, rather than 43,000 individual
        // auto-committed statements on MySQL. Normalize in Java exactly as imports do.
        var entries=new ArrayList<>(keys.entrySet());
        for(int offset=0;offset<entries.size();offset+=500) {
            var batch=entries.subList(offset,Math.min(offset+500,entries.size()));
            String sql="UPDATE vocabulary_word SET term_key=CASE id "+" WHEN ? THEN ?".repeat(batch.size())+" ELSE term_key END WHERE id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")";
            try(var update=connection.prepareStatement(sql)) {
                int index=1;for(var item:batch){update.setString(index++,item.getKey());update.setString(index++,item.getValue());}for(var item:batch)update.setString(index++,item.getKey());update.executeUpdate();
            }
        }
        record Progress(String id,long owner,String word,int correct,int stage,int wrong,boolean mistake,boolean starred,
                        LocalDate due,LocalDate learned,Instant attempt,LocalDate review,String zone) {}
        Map<String,List<Progress>> groups=new LinkedHashMap<>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT p.*,f.zone_id FROM vocabulary_progress p LEFT JOIN vocabulary_profile f ON f.owner_id=p.owner_id")) {
            while(rows.next()) {
                Timestamp at=rows.getTimestamp("last_attempt_at");
                var p=new Progress(rows.getString("id"),rows.getLong("owner_id"),rows.getString("word_id"),rows.getInt("learning_correct"),rows.getInt("review_stage"),rows.getInt("wrong_count"),rows.getBoolean("mistake"),rows.getBoolean("starred"),rows.getObject("due_date",LocalDate.class),rows.getObject("learned_date",LocalDate.class),at==null?null:at.toInstant(),rows.getObject("last_review_date",LocalDate.class),rows.getString("zone_id"));
                groups.computeIfAbsent(p.owner()+"/"+keys.get(p.word()),ignored->new ArrayList<>()).add(p);
            }
        }
        try(var delete=connection.prepareStatement("DELETE FROM vocabulary_progress WHERE id=?");
            var update=connection.prepareStatement("UPDATE vocabulary_progress SET term_key=?,learning_correct=?,review_stage=?,wrong_count=?,mistake=?,starred=?,due_date=?,learned_date=?,last_attempt_at=?,last_review_date=?,updated_at=? WHERE id=?")) {
            for(var group:groups.values()) {
                group.sort(Comparator.comparing((Progress p)->p.attempt()==null?Instant.EPOCH:p.attempt()).reversed().thenComparing(Progress::id));
                Progress latest=group.get(0);
                int correct=(int)Math.min(4,group.stream().mapToLong(Progress::correct).sum());
                int wrong=(int)Math.min(Integer.MAX_VALUE,group.stream().mapToLong(Progress::wrong).sum());
                Progress schedule=group.stream().filter(p->p.correct()>=4).findFirst().orElse(latest);
                LocalDate learned=group.stream().map(Progress::learned).filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
                if(correct>=4 && learned==null)learned=(latest.attempt()==null?Instant.EPOCH:latest.attempt()).atZone(latest.zone()==null?ZoneOffset.UTC:ZoneId.of(latest.zone())).toLocalDate();
                LocalDate due=correct<4?null:schedule.due()==null?learned.plusDays(1):schedule.due();
                for(int i=1;i<group.size();i++){delete.setString(1,group.get(i).id());delete.addBatch();}
                delete.executeBatch();
                update.setString(1,keys.get(latest.word()));update.setInt(2,correct);update.setInt(3,schedule.stage());update.setInt(4,wrong);
                update.setBoolean(5,latest.mistake());update.setBoolean(6,group.stream().anyMatch(Progress::starred));
                update.setObject(7,due);update.setObject(8,learned);update.setTimestamp(9,latest.attempt()==null?null:Timestamp.from(latest.attempt()));update.setObject(10,schedule.review());
                update.setTimestamp(11,latest.attempt()==null?Timestamp.from(Instant.EPOCH):Timestamp.from(latest.attempt()));update.setString(12,latest.id());update.executeUpdate();
            }
        }
    }
}
