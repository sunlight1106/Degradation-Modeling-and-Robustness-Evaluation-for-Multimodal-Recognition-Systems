package com.robustvision.platform.service;

import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

class VocabularyMergeMigrationTest {
    @Test void legacyDuplicateMergePreservesCreditAndLatestReviewWithinEachOwner() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:merge_"+java.util.UUID.randomUUID()+";MODE=MySQL")) {
            var s=c.createStatement();
            s.execute("create table vocabulary_word(id varchar(36) primary key,term varchar(80),term_key varchar(80))");
            s.execute("create table vocabulary_profile(owner_id bigint,zone_id varchar(80))");
            s.execute("create table vocabulary_progress(id varchar(36) primary key,owner_id bigint,word_id varchar(36),learning_correct int,review_stage int,wrong_count int,mistake boolean,starred boolean,due_date date,learned_date date,last_attempt_at timestamp,last_review_date date,term_key varchar(80),updated_at timestamp)");
            s.execute("insert into vocabulary_word values ('a','Work',''),('b',' work ','')");s.execute("insert into vocabulary_profile values(1,'Asia/Shanghai'),(2,'Etc/UTC')");
            s.execute("insert into vocabulary_progress values ('old',1,'a',2,0,1,false,true,null,null,'2026-10-01 10:00:00',null,null,null),('new',1,'b',2,0,2,true,false,null,null,'2026-10-02 10:00:00',null,null,null),('other',2,'a',1,0,0,false,false,null,null,'2026-10-01 10:00:00',null,null,null)");
            db.migration.V23__merge_vocabulary_progress.backfill(c);
            try(var rows=s.executeQuery("select * from vocabulary_progress where owner_id=1")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getString("term_key")).isEqualTo("work");assertThat(rows.getInt("learning_correct")).isEqualTo(4);assertThat(rows.getInt("wrong_count")).isEqualTo(3);assertThat(rows.getBoolean("starred")).isTrue();assertThat(rows.getBoolean("mistake")).isTrue();assertThat(rows.getString("due_date")).isEqualTo("2026-10-03");assertThat(rows.next()).isFalse();
            }
            try(var rows=s.executeQuery("select learning_correct from vocabulary_progress where owner_id=2")){rows.next();assertThat(rows.getInt(1)).isEqualTo(1);}
        }
    }
}
