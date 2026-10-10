package com.robustvision.platform.service;
import java.time.*;
import java.sql.Timestamp;
/** JDBC drivers expose TIMESTAMP as different Java types; never guess a zone. */
final class JdbcTime {
 private JdbcTime(){}
 static Instant instant(Object value){if(value==null)return null;if(value instanceof Instant i)return i;if(value instanceof Timestamp t)return t.toInstant();if(value instanceof OffsetDateTime d)return d.toInstant();if(value instanceof LocalDateTime d)return d.toInstant(ZoneOffset.UTC);throw new IllegalArgumentException("Unsupported timestamp representation");}
}
