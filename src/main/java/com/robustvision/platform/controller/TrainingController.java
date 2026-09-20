package com.robustvision.platform.controller;

import com.robustvision.platform.service.TrainingDatasetService;
import com.robustvision.platform.domain.TaskType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/training")
public class TrainingController {
    private final TrainingDatasetService trainingDatasetService;

    public TrainingController(TrainingDatasetService trainingDatasetService) {
        this.trainingDatasetService = trainingDatasetService;
    }

    @GetMapping("/dataset")
    @PreAuthorize("hasAuthority('report:download') and (hasAuthority('experiment:read') or hasAuthority('experiment:read:any'))")
    public ResponseEntity<byte[]> exportDataset(
            @RequestParam(required = false) TaskType taskType,
            @RequestParam(defaultValue = "all") String variant) {
        byte[] content = trainingDatasetService.exportAccessible(taskType, variant);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("personal-platform-training-dataset.zip").build().toString())
                .body(content);
    }
}
