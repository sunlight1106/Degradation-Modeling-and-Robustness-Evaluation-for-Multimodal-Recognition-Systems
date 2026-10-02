package com.robustvision.platform.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.worker", name = "enabled", havingValue = "true")
public class InferenceQueueWorker {
    private final InferenceQueueService queue;
    private final InferenceService inferenceService;
    private final int maxTasksPerPoll;

    public InferenceQueueWorker(InferenceQueueService queue, InferenceService inferenceService,
                                @Value("${app.worker.max-tasks-per-poll:32}") int maxTasksPerPoll) {
        this.queue = queue;
        this.inferenceService = inferenceService;
        if (maxTasksPerPoll < 1 || maxTasksPerPoll > 1024) {
            throw new IllegalArgumentException("app.worker.max-tasks-per-poll must be between 1 and 1024");
        }
        this.maxTasksPerPoll = maxTasksPerPoll;
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-delay-ms:350}")
    public void poll() {
        // Do not impose the idle polling delay on every task in an existing backlog.
        // Pop only when ready to process; never prefetch jobs that could be lost on shutdown.
        for (int processed = 0; processed < maxTasksPerPoll && !Thread.currentThread().isInterrupted(); processed++) {
            String taskId = queue.poll();
            if (taskId == null) return;
            if (!taskId.isBlank()) inferenceService.processQueuedTask(taskId);
        }
    }
}
