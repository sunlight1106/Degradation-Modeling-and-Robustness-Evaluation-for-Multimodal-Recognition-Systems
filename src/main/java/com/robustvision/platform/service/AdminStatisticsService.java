package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.dto.AdminDtos;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class AdminStatisticsService {
    private final JdbcTemplate jdbc;
    public AdminStatisticsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public AdminDtos.Statistics statistics(int days) {
        if (days != 7 && days != 30 && days != 90) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "可选择最近 7、30 或 90 天");
        Instant now = Instant.now();
        // Stored timestamps and grouping use UTC. We label this explicitly rather than
        // silently grouping a different day on the database and the browser.
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC), start = today.minusDays(days - 1L);
        Timestamp from = Timestamp.from(start.atStartOfDay(ZoneOffset.UTC).toInstant());
        Timestamp until = Timestamp.from(today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        Map<LocalDate, Long> registrations = daily("app_user", "COUNT(*)", from, until);
        Map<LocalDate, Long> logins = daily("user_session", "COUNT(DISTINCT user_id)", from, until);
        Map<LocalDate, Long> notes = daily("note", "COUNT(*)", from, until);
        Map<LocalDate, Long> calls = daily("personal_ai_usage", "COUNT(*)", from, until);
        Map<LocalDate, Long> tasks = daily("inference_task", "COUNT(*)", from, until);
        List<AdminDtos.Day> series = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            series.add(new AdminDtos.Day(day, registrations.getOrDefault(day, 0L), logins.getOrDefault(day, 0L),
                    notes.getOrDefault(day, 0L), calls.getOrDefault(day, 0L), tasks.getOrDefault(day, 0L)));
        }
        return new AdminDtos.Statistics(count("SELECT COUNT(*) FROM app_user"),
                count("SELECT COUNT(*) FROM app_user WHERE status = 'ACTIVE' AND (access_expires_at IS NULL OR access_expires_at > ?)", Timestamp.from(now)),
                count("SELECT COUNT(*) FROM app_user WHERE status = 'ACTIVE' AND access_expires_at <= ?", Timestamp.from(now)),
                count("SELECT COUNT(*) FROM app_user WHERE status = 'DISABLED'"),
                count("SELECT COUNT(DISTINCT s.user_id) FROM user_session s JOIN app_user u ON s.user_id = u.id WHERE s.revoked_at IS NULL AND s.expires_at > ? AND s.last_seen_at >= ? AND u.status = 'ACTIVE' AND (u.access_expires_at IS NULL OR u.access_expires_at > ?)", Timestamp.from(now), Timestamp.from(now.minusSeconds(900)), Timestamp.from(now)),
                count("SELECT COUNT(*) FROM app_role"), count("SELECT COUNT(*) FROM note WHERE deleted_at IS NULL"),
                count("SELECT COUNT(*) FROM file_asset"), count("SELECT COALESCE(SUM(size_bytes), 0) FROM file_asset"),
                count("SELECT COUNT(*) FROM personal_ai_usage"), count("SELECT COUNT(*) FROM inference_task"),
                "UTC", now, series);
    }

    private long count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private Map<LocalDate, Long> daily(String table, String aggregate, Timestamp from, Timestamp until) {
        // Table/aggregate are fixed internal constants, never request data. Aggregate
        // in SQL so a long-lived platform never transfers every event into memory.
        Map<LocalDate, Long> values = new HashMap<>();
        jdbc.query("SELECT CAST(created_at AS DATE) AS stat_date, " + aggregate + " AS total FROM " + table
                + " WHERE created_at >= ? AND created_at < ? GROUP BY CAST(created_at AS DATE)",
                rs -> { values.put(rs.getDate("stat_date").toLocalDate(), rs.getLong("total")); }, from, until);
        return values;
    }
}
