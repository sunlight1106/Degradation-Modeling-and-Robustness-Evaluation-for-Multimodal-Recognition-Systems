package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.NoteExperimentSourceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/notes/sources/experiments")
@PreAuthorize("hasAuthority('note:read')")
public class NoteSourceController {
    private final NoteExperimentSourceService service;
    public NoteSourceController(NoteExperimentSourceService service) { this.service = service; }
    @GetMapping public ApiResponse<List<NoteExperimentSourceService.SourceView>> list() {
        return ApiResponse.ok(service.list());
    }
    @PostMapping("/preview") public ApiResponse<NoteExperimentSourceService.Preview> preview(@Valid @RequestBody Request request) {
        return ApiResponse.ok(service.preview(request.taskIds()));
    }
    public record Request(@NotEmpty @Size(max = 20) List<@Size(max = 38) String> taskIds) {}
}
