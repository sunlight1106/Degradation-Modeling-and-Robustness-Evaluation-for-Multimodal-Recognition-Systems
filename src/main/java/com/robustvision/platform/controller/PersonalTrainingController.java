package com.robustvision.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.PersonalTrainingService;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/personal-ai/training")
@PreAuthorize("isAuthenticated()")
public class PersonalTrainingController {
    private final PersonalTrainingService service;
    public PersonalTrainingController(PersonalTrainingService service){this.service=service;}
    @GetMapping("/environment") public ApiResponse<JsonNode> environment(){return ApiResponse.ok(service.environment());}
    @GetMapping("/jobs") public ApiResponse<JsonNode> list(){return ApiResponse.ok(service.list());}
    @PostMapping("/jobs") public ApiResponse<JsonNode> create(@RequestBody JsonNode request){return ApiResponse.ok(service.create(request));}
    @GetMapping("/jobs/{id}") public ApiResponse<JsonNode> get(@PathVariable UUID id){return ApiResponse.ok(service.get(id));}
    @PostMapping("/jobs/{id}/cancel") public ApiResponse<JsonNode> cancel(@PathVariable UUID id){return ApiResponse.ok(service.cancel(id));}
    @PostMapping("/jobs/{id}/predict") public ApiResponse<JsonNode> predict(@PathVariable UUID id,@RequestBody JsonNode request){return ApiResponse.ok(service.predict(id,request));}
    @DeleteMapping("/jobs/{id}") public ApiResponse<Void> delete(@PathVariable UUID id){service.delete(id);return ApiResponse.ok(null);}
    @GetMapping("/jobs/{id}/download") public ResponseEntity<byte[]> download(@PathVariable UUID id){return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip")).header("Cache-Control","no-store")
            .header("Content-Disposition","attachment; filename=training-"+id+".zip").body(service.download(id));}
}
