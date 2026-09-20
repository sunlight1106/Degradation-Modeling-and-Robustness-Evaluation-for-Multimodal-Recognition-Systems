package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.domain.FileAssetEntity;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.service.FileService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('file:write')")
    public ApiResponse<ApiDtos.FileView> upload(@RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(fileService.upload(file));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('file:read') or hasAuthority('file:read:any')")
    public ApiResponse<List<ApiDtos.FileView>> list() {
        return ApiResponse.ok(fileService.listAccessible());
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("hasAuthority('file:read') or hasAuthority('file:read:any')")
    public ResponseEntity<Resource> content(@PathVariable String id) {
        return fileResponse(id, false);
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("hasAuthority('file:read') or hasAuthority('file:read:any')")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        return fileResponse(id, true);
    }

    private ResponseEntity<Resource> fileResponse(String id, boolean attachment) {
        FileAssetEntity asset = fileService.requireAccessible(id);
        ContentDisposition disposition = ContentDisposition.builder(attachment ? "attachment" : "inline")
                .filename(asset.getOriginalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getContentType()))
                .contentLength(asset.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(fileService.asResource(asset));
    }
}

