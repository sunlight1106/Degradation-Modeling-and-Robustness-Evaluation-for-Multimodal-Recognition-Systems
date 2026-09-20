package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class InferenceQueueService {
    private final StringRedisTemplate redis;
    private final String mode;
    private final String queueKey;

    public InferenceQueueService(StringRedisTemplate redis,
                                 @Value("${app.queue.mode:inline}") String mode,
                                 @Value("${app.queue.key:personal:inference:jobs}") String queueKey) {
        this.redis = redis;
        this.mode = mode;
        this.queueKey = queueKey;
    }

    public boolean inline() { return !"redis".equalsIgnoreCase(mode); }

    public void enqueue(String taskId) {
        try { redis.opsForList().leftPush(queueKey, taskId); }
        catch (Exception exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "INFERENCE_QUEUE_UNAVAILABLE", "推理队列暂时不可用");
        }
    }

    public String poll() {
        if (inline()) return null;
        try { return redis.opsForList().rightPop(queueKey); }
        catch (Exception ignored) { return null; }
    }
}
