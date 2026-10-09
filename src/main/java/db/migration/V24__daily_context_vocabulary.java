package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.service.VocabularyTermKey;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.zip.CRC32;

/** Original context course. Existing books and user progress are never overwritten. */
public class V24__daily_context_vocabulary extends BaseJavaMigration {
    private InputStream resource(){return Objects.requireNonNull(getClass().getResourceAsStream("/vocabulary/daily-lessons.json"));}
    @Override public Integer getChecksum(){try(var in=resource()){var crc=new CRC32();crc.update(in.readAllBytes());return (int)crc.getValue();}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public void migrate(Context context)throws Exception {
        var json=new ObjectMapper();
        try(var input=resource();var book=context.getConnection().prepareStatement("INSERT INTO vocabulary_book (id,owner_id,title,description,attribution,level,created_at) VALUES ('vocab-context',NULL,?,?,?,?,?)");
            var word=context.getConnection().prepareStatement("INSERT INTO vocabulary_word (id,book_id,term,term_key,ipa,pos,meaning,example_text,example_translation,distractors,sort_order,lesson_json) VALUES (?,'vocab-context',?,?,?,?,?,?,?,?,?,?)")) {
            var cards=json.readTree(input);book.setString(1,"每日 · 场景与搭配");book.setString(2,"86 词带练：从画面理解，记固定搭配，再遮住答案回忆。穿插抽查，次日混合复习。");book.setString(3,"原创场景、例句与带练课程");book.setString(4,"starter");book.setTimestamp(5,Timestamp.from(Instant.parse("2026-10-09T00:00:00Z")));book.executeUpdate();
            int order=0;
            for(var card:cards){
                String meaning=card.path("meaning").asText();List<String> distractors=new ArrayList<>();
                for(var other:cards){String value=other.path("meaning").asText();if(!value.equals(meaning)&&!value.contains(meaning)&&!meaning.contains(value)&&!distractors.contains(value))distractors.add(value);if(distractors.size()==3)break;}
                var lesson=json.createObjectNode();lesson.set("memoryCue",card.path("memoryCue"));lesson.set("usageNote",card.path("usageNote"));lesson.set("collocations",card.path("collocations"));
                String term=card.path("term").asText();word.setString(1,"vocab-context-"+order);word.setString(2,term);word.setString(3,VocabularyTermKey.of(term));word.setString(4,card.path("ipa").asText());word.setString(5,card.path("pos").asText());word.setString(6,meaning);word.setString(7,card.path("example").asText());word.setString(8,card.path("exampleTranslation").asText());word.setString(9,json.writeValueAsString(distractors));word.setInt(10,order++);word.setString(11,json.writeValueAsString(lesson));word.addBatch();
            }word.executeBatch();
        }
    }
}
