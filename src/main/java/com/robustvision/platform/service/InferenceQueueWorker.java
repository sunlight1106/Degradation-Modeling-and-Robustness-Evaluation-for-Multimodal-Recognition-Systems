package com.robustvision.platform.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true")
public class InferenceQueueWorker {
    private final InferenceQueueService queue;
    private final InferenceService inferenceService;

    public InferenceQueueWorker(InferenceQueueService queue, InferenceService inferenceService) {
        this.queue = queue;
        this.inferenceService = inferenceService;
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-delay-ms:350}")
    public void poll() {
        String taskId = queue.poll();
        if (taskId != null && !taskId.isBlank()) inferenceService.processQueuedTask(taskId);
    }
}
