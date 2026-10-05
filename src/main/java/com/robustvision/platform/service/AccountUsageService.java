package com.robustvision.platform.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** All-time metadata aggregates scoped to the authenticated user, without loading private contents. */
@Service
public class AccountUsageService {
    private final JdbcTemplate jdbc;
    private final CurrentUserService current;
    public AccountUsageService(JdbcTemplate jdbc, CurrentUserService current) {this.jdbc=jdbc;this.current=current;}
    @Transactional(readOnly=true)
    public Summary summary() {
        Long owner=current.requireCurrent().getId();
        Ai ai=jdbc.queryForObject("""
                SELECT COUNT(*) total,
                  COALESCE(SUM(CASE WHEN status='SUCCEEDED' THEN 1 ELSE 0 END),0) succeeded,
                  COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) failed,
                  COALESCE(SUM(input_tokens),0) input_tokens,COALESCE(SUM(output_tokens),0) output_tokens,
                  COALESCE(SUM(CASE WHEN input_tokens IS NULL OR output_tokens IS NULL THEN 1 ELSE 0 END),0) unknown_calls
                FROM personal_ai_usage WHERE owner_id=?
                """,(rs,row)->new Ai(rs.getLong("total"),rs.getLong("succeeded"),rs.getLong("failed"),rs.getLong("input_tokens"),rs.getLong("output_tokens"),rs.getLong("unknown_calls")),owner);
        Experiments experiments=jdbc.queryForObject("""
                SELECT COUNT(*) total,COALESCE(SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END),0) completed,
                  COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) failed
                FROM inference_task WHERE requested_by=?
                """,(rs,row)->new Experiments(rs.getLong("total"),rs.getLong("completed"),rs.getLong("failed")),owner);
        Files files=jdbc.queryForObject("SELECT COUNT(*) count,COALESCE(SUM(size_bytes),0) bytes FROM file_asset WHERE owner_id=?",
                (rs,row)->new Files(rs.getLong("count"),rs.getLong("bytes")),owner);
        return new Summary(ai,experiments,files,
                jdbc.queryForObject("SELECT COUNT(*) FROM note WHERE owner_id=?",Long.class,owner),
                jdbc.queryForObject("SELECT COUNT(*) FROM personal_recognition_result WHERE owner_id=?",Long.class,owner));
    }
    public record Ai(long total,long succeeded,long failed,long knownInputTokens,long knownOutputTokens,long unknownUsageCalls){}
    public record Experiments(long total,long completed,long failed){}
    public record Files(long count,long bytes){}
    public record Summary(Ai ai,Experiments experiments,Files files,long noteCount,long recognitionCount){}
}
