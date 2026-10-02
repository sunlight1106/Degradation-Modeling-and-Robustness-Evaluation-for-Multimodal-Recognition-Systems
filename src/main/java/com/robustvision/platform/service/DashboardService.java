package com.robustvision.platform.service;

import com.robustvision.platform.domain.InferenceTaskEntity;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.InferenceTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DashboardService {
    private final InferenceService inferenceService;
    private final InferenceTaskRepository taskRepository;
    private final CurrentUserService currentUserService;

    public DashboardService(InferenceService inferenceService, InferenceTaskRepository taskRepository,
                            CurrentUserService currentUserService) {
        this.inferenceService = inferenceService;
        this.taskRepository = taskRepository;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public ApiDtos.DashboardSummary summary() {
        UserEntity current = currentUserService.requireCurrent();
        boolean readAny = currentUserService.hasPermission(current, "experiment:read:any");
        InferenceTaskRepository.SummaryStatistics stats = readAny
                ? taskRepository.summarizeAll() : taskRepository.summarizeByRequestedById(current.getId());
        List<InferenceTaskEntity> recent = readAny
                ? taskRepository.findTop5ByOrderByCreatedAtDescIdDesc()
                : taskRepository.findTop5ByRequestedByIdOrderByCreatedAtDescIdDesc(current.getId());
        double successRate = stats.getTotal() == 0 ? 0 : stats.getCompleted() * 100.0 / stats.getTotal();
        // SQL AVG excludes rows with either confidence null, matching the original stream.
        double averageLift = stats.getAverageLift() == null ? 0 : stats.getAverageLift();
        return new ApiDtos.DashboardSummary(
                stats.getTotal(), stats.getCompleted(), stats.getFailed(), round(successRate), round(averageLift),
                recent.stream().map(inferenceService::toView).toList());
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
