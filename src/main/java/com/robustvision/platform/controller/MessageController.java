package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.service.MessageService;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/messages")
@PreAuthorize("hasAuthority('message:read')")
public class MessageController {
    private final MessageService service;
    public MessageController(MessageService service) { this.service = service; }
    @GetMapping("/directory") public ApiResponse<List<ApiDtos.UserDirectoryView>> directory() { return ApiResponse.ok(service.directory()); }
    @GetMapping("/inbox") public ApiResponse<List<ApiDtos.MessageView>> inbox() { return ApiResponse.ok(service.inbox()); }
    @GetMapping("/sent") public ApiResponse<List<ApiDtos.MessageView>> sent() { return ApiResponse.ok(service.sent()); }
    @GetMapping("/{id}") public ApiResponse<ApiDtos.MessageView> detail(@PathVariable String id) { return ApiResponse.ok(service.detail(id)); }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ApiDtos.MessageView> send(@RequestParam List<Long> recipientIds, @RequestParam String subject,
            @RequestParam String body, @RequestPart(required = false) List<MultipartFile> files) {
        return ApiResponse.ok(service.send(recipientIds, subject, body, files));
    }
    @GetMapping("/{messageId}/attachments/{attachmentId}")
    public ResponseEntity<org.springframework.core.io.Resource> attachment(@PathVariable String messageId, @PathVariable Long attachmentId) {
        MessageService.AttachmentDownload item = service.attachment(messageId, attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(item.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(item.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(item.resource());
    }
}
