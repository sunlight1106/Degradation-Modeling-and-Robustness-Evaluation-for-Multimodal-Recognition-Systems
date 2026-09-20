package com.robustvision.platform.service;

import com.robustvision.platform.domain.InferenceStatus;
import com.robustvision.platform.dto.ApiDtos;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DashboardService {
    private final InferenceService inferenceService;

    public DashboardService(InferenceService inferenceService) {
        this.inferenceService = inferenceService;
    }

    public ApiDtos.DashboardSummary summary() {
        List<ApiDtos.InferenceView> tasks = inferenceService.listAccessible();
        long completed = tasks.stream().filter(task -> task.status() == InferenceStatus.COMPLETED).count();
        long failed = tasks.stream().filter(task -> task.status() == InferenceStatus.FAILED).count();
        double successRate = tasks.isEmpty() ? 0 : completed * 100.0 / tasks.size();
        double averageLift = tasks.stream()
                .filter(task -> task.baselineConfidence() != null && task.optimizedConfidence() != null)
                .mapToDouble(task -> task.optimizedConfidence() - task.baselineConfidence())
                .average().orElse(0.0);
        return new ApiDtos.DashboardSummary(
                tasks.size(), completed, failed, round(successRate), round(averageLift), tasks.stream().limit(5).toList());
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
