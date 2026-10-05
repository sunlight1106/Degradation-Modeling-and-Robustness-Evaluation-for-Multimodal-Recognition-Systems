package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.*;

/** Bounded per-process admission control. Distributed deployments also need gateway-wide quotas. */
@Component
public class PersonalAiRateLimiter {
    private final Map<Long, Window> windows = new HashMap<>();
    private final Clock clock;
    private int totalInFlight;
    public PersonalAiRateLimiter() { this(Clock.systemUTC()); }
    PersonalAiRateLimiter(Clock clock) { this.clock = clock; }
    private static class Window { long start; int previews; int executions; int active; Window(long start) { this.start = start; } }
    public synchronized void preview(Long owner) { Window w = window(owner); if (++w.previews > 30) throw limited(); }
    public synchronized Permit acquire(Long owner) {
        Window w = window(owner);
        if (w.active > 0 || w.executions >= 12 || totalInFlight >= 16) throw limited();
        w.executions++; w.active++; totalInFlight++;
        return new Permit(owner);
    }
    private Window window(Long owner) {
        long now = clock.millis();
        windows.entrySet().removeIf(e -> e.getValue().active == 0 && now - e.getValue().start >= 120000);
        Window w = windows.get(owner);
        if (w == null) {
            if (windows.size() >= 10000) throw limited();
            w = new Window(now); windows.put(owner, w);
        }
        if (now - w.start >= 60000) { w.start = now; w.previews = 0; w.executions = 0; }
        return w;
    }
    public final class Permit implements AutoCloseable {
        private final Long owner; private boolean closed;
        private Permit(Long owner) { this.owner = owner; }
        public void close() { synchronized (PersonalAiRateLimiter.this) {
            if (!closed) { closed = true; windows.get(owner).active--; totalInFlight--; }
        } }
    }
    private static BusinessException limited() { return new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
            "PERSONAL_AI_RATE_LIMIT", "个人 AI 请求过于频繁或已有任务进行中，请稍后重试"); }
}
