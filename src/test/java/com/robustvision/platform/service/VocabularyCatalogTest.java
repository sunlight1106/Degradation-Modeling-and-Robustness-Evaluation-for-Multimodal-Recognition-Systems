package com.robustvision.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.security.MessageDigest;
import static org.assertj.core.api.Assertions.assertThat;

class VocabularyCatalogTest {
    @Test void lessonsHavePhoneticsAndDailyCourseHasScenesAndPatterns() throws Exception {
        var json=new ObjectMapper();var manifest=json.readTree(getClass().getResourceAsStream("/vocabulary/lessons-manifest.json"));
        byte[] raw=Objects.requireNonNull(getClass().getResourceAsStream("/vocabulary/lessons-v1.json.gz")).readAllBytes();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))).isEqualTo(manifest.path("sha256").asText());
        var lessons=json.readTree(new GZIPInputStream(new java.io.ByteArrayInputStream(raw)));assertThat(lessons.size()).isEqualTo(manifest.path("uniqueWords").asInt());
        for(var card:lessons){assertThat(card.path("ipa").asText()).isNotBlank().doesNotContain("*");assertThat(card.path("usageNote").asText()).isNotBlank();}
        var daily=json.readTree(getClass().getResourceAsStream("/vocabulary/daily-lessons.json"));assertThat(daily.size()).isEqualTo(86);
        for(var card:daily){assertThat(card.path("memoryCue").asText()).isNotBlank();assertThat(card.path("collocations").size()).isPositive();assertThat(card.path("example").asText()).isNotBlank();}
    }
    @Test void shippedCatalogMatchesManifestAndHasValidUnambiguousOptions() throws Exception {
        var json=new ObjectMapper();
        var manifest=json.readTree(getClass().getResourceAsStream("/vocabulary/catalog.json"));
        byte[] compressed;
        try(var in=Objects.requireNonNull(getClass().getResourceAsStream("/vocabulary/v17.json.gz"))) { compressed=in.readAllBytes(); }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(compressed))).isEqualTo(manifest.path("catalogSha256").asText());
        var data=json.readTree(new GZIPInputStream(new java.io.ByteArrayInputStream(compressed)));
        var entries=data.path("entries");assertThat(entries.size()).isEqualTo(manifest.path("uniqueWords").asInt());
        Set<String> terms=new HashSet<>();
        for(var entry:entries) {
            assertThat(terms.add(entry.path("term").asText())).isTrue();
            assertThat(entry.path("term").asText()).matches("[a-z][a-z '\\-]{0,79}");
            assertThat(entry.path("meaning").asText()).isNotBlank().hasSizeLessThanOrEqualTo(160);
            assertThat(entry.path("ipa").asText()).hasSizeLessThanOrEqualTo(120);
            assertThat(entry.path("pos").asText()).hasSizeLessThanOrEqualTo(24);
            Set<String> choices=new HashSet<>();choices.add(entry.path("meaning").asText());
            for(var option:entry.path("distractors")) {
                assertThat(option.asText()).isNotBlank().hasSizeLessThanOrEqualTo(160);
                assertThat(choices.add(option.asText())).isTrue();
            }
            assertThat(choices).hasSize(4);
        }
        int total=0;
        for(var book:data.path("books")) {
            Set<Integer> seen=new HashSet<>();
            for(var ref:book.path("words")) {assertThat(ref.asInt()).isBetween(0,entries.size()-1);assertThat(seen.add(ref.asInt())).isTrue();}
            assertThat(seen.size()).isGreaterThan(100);total+=seen.size();
        }
        assertThat(total).isEqualTo(manifest.path("totalEntries").asInt());
        assertThat(data.path("books").size()).isEqualTo(14);
        assertThat(new db.migration.V17__expanded_vocabulary_catalog().getChecksum()).isNotNull();
    }
}
