package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;

/** Immutable offline corpus; Flyway runs once under its shared migration lock. */
public class V17__expanded_vocabulary_catalog extends BaseJavaMigration {
    private InputStream resource() {
        return Objects.requireNonNull(getClass().getResourceAsStream("/vocabulary/v17.json.gz"), "Missing vocabulary catalog");
    }
    @Override public Integer getChecksum() {
        try (var input = resource()) { var crc = new CRC32(); crc.update(input.readAllBytes()); return (int) crc.getValue(); }
        catch (Exception error) { throw new IllegalStateException("Cannot checksum vocabulary catalog", error); }
    }
    @Override public void migrate(Context context) throws Exception {
        var json = new ObjectMapper();
        try (var input = new GZIPInputStream(resource());
             var book = context.getConnection().prepareStatement("INSERT INTO vocabulary_book (id,owner_id,title,description,attribution,level,created_at) VALUES (?,NULL,?,?,?,?,?)");
             var word = context.getConnection().prepareStatement("INSERT INTO vocabulary_word (id,book_id,term,ipa,pos,meaning,example_text,example_translation,distractors,sort_order) VALUES (?,?,?,?,?,?,'','',?,?)")) {
            var data = json.readTree(input); var entries = data.path("entries");
            for (var b : data.path("books")) {
                String id = b.path("id").asText();
                book.setString(1,id); book.setString(2,b.path("title").asText()); book.setString(3,b.path("description").asText());
                book.setString(4,"ECDICT · MIT · 开源词汇选编"); book.setString(5,b.path("level").asText());
                book.setTimestamp(6,Timestamp.from(Instant.parse("2026-10-06T00:00:00Z"))); book.executeUpdate();
                int order = 0;
                for (var reference : b.path("words")) {
                    var entry = entries.get(reference.asInt());
                    word.setString(1,id+"-"+reference.asInt()); word.setString(2,id); word.setString(3,entry.path("term").asText());
                    word.setString(4,entry.path("ipa").asText()); word.setString(5,entry.path("pos").asText()); word.setString(6,entry.path("meaning").asText());
                    word.setString(7,json.writeValueAsString(entry.path("distractors"))); word.setInt(8,order++); word.addBatch();
                    if (order % 500 == 0) word.executeBatch();
                }
                word.executeBatch();
            }
        }
    }
}
