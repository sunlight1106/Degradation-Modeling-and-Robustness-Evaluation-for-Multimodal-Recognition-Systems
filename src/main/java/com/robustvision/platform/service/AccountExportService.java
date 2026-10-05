package com.robustvision.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.dto.VocabularyDtos.ImportRequest;
import com.robustvision.platform.dto.VocabularyDtos.ImportWord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit column allowlists: never serialize persistence entities or secret-bearing tables wholesale. */
@Service
public class AccountExportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public AccountExportService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public Map<String, Object> export(UserEntity user, ApiDtos.UserView profile) {
        Long id = user.getId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", 2);
        data.put("exportedAt", Instant.now());
        data.put("profile", profile);
        data.put("notes", rows("SELECT id, title, body, tags, status, created_at, updated_at FROM note WHERE owner_id = ? ORDER BY created_at, id", id));
        data.put("noteReferences", rows("SELECT r.id, r.note_id, r.reference_type, r.reference_id, r.label, r.sort_order FROM note_reference r JOIN note n ON n.id = r.note_id WHERE n.owner_id = ? ORDER BY r.id", id));
        data.put("knowledgeTopics", rows("SELECT id, domain, name, description, sort_order, created_at, updated_at FROM knowledge_topic WHERE owner_id = ? ORDER BY id", id));
        data.put("knowledgeEntries", rows("SELECT e.id, e.topic_id, e.title, e.summary, e.body, e.tags, e.sort_order, e.created_at, e.updated_at FROM knowledge_entry e JOIN knowledge_topic t ON t.id = e.topic_id WHERE e.owner_id = ? AND (t.owner_id IS NULL OR t.owner_id = e.owner_id) ORDER BY e.created_at, e.id", id));
        data.put("files", rows("SELECT id, original_name, content_type, size_bytes, source, scan_status, created_at FROM file_asset WHERE owner_id = ? ORDER BY created_at, id", id));
        data.put("inferenceHistory", rows("SELECT id, trace_id, task_type, status, model_id, provider, enhancement_enabled, baseline_confidence, optimized_confidence, baseline_latency_ms, optimized_latency_ms, input_tokens, output_tokens, cost_cny, created_at, completed_at FROM inference_task WHERE requested_by = ? ORDER BY created_at, id", id));
        data.put("aiSettings", rows("SELECT provider, model, enabled, updated_at FROM personal_ai_setting WHERE owner_id = ? ORDER BY provider", id));
        data.put("aiMemories", rows("SELECT id, title, body, enabled, revision, created_at, updated_at FROM personal_ai_memory WHERE owner_id = ? ORDER BY created_at, id", id));
        data.put("aiUsage", rows("SELECT id, provider, model, action, status, input_tokens, output_tokens, error_code, created_at FROM personal_ai_usage WHERE owner_id = ? ORDER BY created_at, id", id));
        data.put("wallet", rows("SELECT balance_cny, monthly_quota_cny, month_spent_cny, quota_period_start, updated_at FROM user_wallet WHERE user_id = ?", id));
        data.put("walletLedger", rows("SELECT id, type, amount, balance_after, reference_id, description, created_at FROM wallet_ledger WHERE user_id = ? ORDER BY created_at, id", id));
        data.put("rechargeOrders", rows("SELECT id, method, amount, status, expires_at, created_at, paid_at FROM recharge_order WHERE user_id = ? ORDER BY created_at, id", id));
        data.put("ownedWorkspaces", rows("SELECT id, name, slug, color, created_at, updated_at FROM workspace WHERE owner_id = ? ORDER BY id", id));
        data.put("workspaceMemberships", rows("SELECT workspace_id, member_role, created_at FROM workspace_member WHERE user_id = ? ORDER BY workspace_id", id));
        data.put("sentMessages", rows("SELECT id, subject, body, created_at FROM internal_message WHERE sender_id = ? ORDER BY created_at, id", id));
        data.put("personalRecognition", rows("SELECT r.id, r.file_id, r.file_name, r.provider, r.model, r.task_type, r.result_text, r.input_tokens, r.output_tokens, r.created_at FROM personal_recognition_result r JOIN file_asset f ON f.id = r.file_id WHERE r.owner_id = ? AND f.owner_id = r.owner_id ORDER BY r.created_at, r.id", id));
        data.put("vocabularyBooks", rows("SELECT id, title, description, attribution, level, created_at FROM vocabulary_book WHERE owner_id = ? ORDER BY created_at, id", id));
        data.put("vocabularyWords", rows("SELECT w.id, w.book_id, w.term, w.ipa, w.pos, w.meaning, w.example_text, w.example_translation, w.distractors, w.sort_order FROM vocabulary_word w JOIN vocabulary_book b ON b.id = w.book_id WHERE b.owner_id = ? ORDER BY w.book_id, w.sort_order, w.id", id));
        data.put("vocabularyBookImports", bookImports(id));
        data.put("vocabularyProfile", rows("SELECT zone_id, daily_goal, selected_book_id, updated_at FROM vocabulary_profile WHERE owner_id = ?", id));
        data.put("vocabularyProgress", rows("SELECT p.id, p.word_id, w.book_id, w.term, p.learning_correct, p.review_stage, p.wrong_count, p.mistake, p.starred, p.due_date, p.learned_date, p.last_attempt_at, p.last_review_date FROM vocabulary_progress p JOIN vocabulary_word w ON w.id = p.word_id JOIN vocabulary_book b ON b.id = w.book_id WHERE p.owner_id = ? AND (b.owner_id IS NULL OR b.owner_id = p.owner_id) ORDER BY p.id", id));
        data.put("exclusions", List.of("Passwords and password hashes", "API keys and encrypted credentials", "Session, sharing and payment tokens", "Raw uploaded media and internal storage paths", "Raw upstream inference payloads", "Vocabulary question and answer snapshots", "Other users' private data"));
        return data;
    }
    /** Portable content only: new ownership/IDs on import, no progress, and rights must be reconfirmed. */
    private List<ImportRequest> bookImports(Long owner) {
        return jdbc.query("SELECT id, title, description, attribution FROM vocabulary_book WHERE owner_id = ? ORDER BY created_at, id",
                (book, rowNumber) -> new ImportRequest(book.getString("title"), book.getString("description"), book.getString("attribution"), false,
                        jdbc.query("SELECT w.term, w.ipa, w.pos, w.meaning, w.example_text, w.example_translation, w.distractors FROM vocabulary_word w JOIN vocabulary_book b ON b.id = w.book_id WHERE w.book_id = ? AND b.owner_id = ? ORDER BY w.sort_order, w.id",
                                (word, wordNumber) -> new ImportWord(word.getString("term"), word.getString("ipa"), word.getString("pos"), word.getString("meaning"),
                                        word.getString("example_text"), word.getString("example_translation"), distractors(word.getString("distractors"))), book.getString("id"), owner), 1), owner);
    }
    private List<String> distractors(String encoded) {
        try { return json.readValue(encoded, new TypeReference<List<String>>() {}); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Vocabulary export data is invalid", exception); }
    }
    private List<Map<String, Object>> rows(String sql, Long id) {
        return jdbc.query(sql, (rs, rowNumber) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            var metadata = rs.getMetaData();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                Object value = rs.getObject(column);
                if (value instanceof Timestamp timestamp) value = timestamp.toInstant();
                row.put(metadata.getColumnLabel(column), value);
            }
            return row;
        }, id);
    }
}
