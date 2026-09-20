package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.service.InferenceService;
import com.robustvision.platform.service.LogExportService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/inference/tasks")
public class InferenceController {
    private final InferenceService inferenceService;
    private final LogExportService logExportService;

    public InferenceController(InferenceService inferenceService, LogExportService logExportService) {
        this.inferenceService = inferenceService;
        this.logExportService = logExportService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('experiment:run')")
    public ApiResponse<ApiDtos.InferenceView> create(@Valid @RequestBody ApiDtos.CreateInferenceRequest request) {
        return ApiResponse.ok(inferenceService.create(request));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('experiment:read') or hasAuthority('experiment:read:any')")
    public ApiResponse<List<ApiDtos.InferenceView>> list() {
        return ApiResponse.ok(inferenceService.listAccessible());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('experiment:read') or hasAuthority('experiment:read:any')")
    public ApiResponse<ApiDtos.InferenceView> detail(@PathVariable String id) {
        return ApiResponse.ok(inferenceService.requireView(id));
    }

    @GetMapping(value = "/{id}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('report:download')")
    public ResponseEntity<byte[]> report(@PathVariable String id) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("experiment-" + id + ".json", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(inferenceService.report(id));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('report:download')")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "json") String format) {
        LogExportService.ExportFile file = logExportService.export(inferenceService.listAccessible(), format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
