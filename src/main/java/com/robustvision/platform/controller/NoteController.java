package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.service.NoteAssistService;
import com.robustvision.platform.service.NoteExportService;
import com.robustvision.platform.service.NoteService;
import com.robustvision.platform.service.NoteShareService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 笔记接口：CRUD、引用管理、AI 整理、导出与分享。 */
@RestController
@RequestMapping("/api/v1/notes")
public class NoteController {

    private final NoteService noteService;
    private final NoteAssistService assistService;
    private final NoteExportService exportService;
    private final NoteShareService shareService;

    public NoteController(NoteService noteService, NoteAssistService assistService,
                          NoteExportService exportService, NoteShareService shareService) {
        this.noteService = noteService;
        this.assistService = assistService;
        this.exportService = exportService;
        this.shareService = shareService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('note:read')")
    public ApiResponse<List<ApiDtos.NoteSummaryView>> list(@RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(noteService.list(status, keyword));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('note:read')")
    public ApiResponse<ApiDtos.NoteView> get(@PathVariable String id) {
        return ApiResponse.ok(noteService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('note:write')")
    public ApiResponse<ApiDtos.NoteView> create(@Valid @RequestBody ApiDtos.CreateNoteRequest request) {
        return ApiResponse.ok(noteService.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('note:write')")
    public ApiResponse<ApiDtos.NoteView> update(@PathVariable String id,
                                                @Valid @RequestBody ApiDtos.UpdateNoteRequest request) {
        return ApiResponse.ok(noteService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('note:write')")
    public ApiResponse<Void> delete(@PathVariable String id) {
        noteService.delete(id);
        return ApiResponse.ok(null);
    }

    // ------------------------------------------------------------------
    // 引用
    // ------------------------------------------------------------------

    @PostMapping("/{id}/references")
    @PreAuthorize("hasAuthority('note:write')")
    public ApiResponse<ApiDtos.NoteView> addReference(@PathVariable String id,
                                                      @Valid @RequestBody ApiDtos.AddNoteReferenceRequest request) {
        return ApiResponse.ok(noteService.addReference(id, request));
    }

    @DeleteMapping("/{id}/references/{referenceId}")
    @PreAuthorize("hasAuthority('note:write')")
    public ApiResponse<ApiDtos.NoteView> removeReference(@PathVariable String id, @PathVariable Long referenceId) {
        return ApiResponse.ok(noteService.removeReference(id, referenceId));
    }

    // ------------------------------------------------------------------
    // AI 整理（无密钥时本地规则，响应如实标注 engine）
    // ------------------------------------------------------------------

    @PostMapping("/{id}/assist")
    @PreAuthorize("hasAuthority('note:write')")
    public ApiResponse<ApiDtos.NoteAssistResponse> assist(@PathVariable String id,
                                                          @Valid @RequestBody ApiDtos.NoteAssistRequest request) {
        // 以数据库中已保存的笔记正文为准，防止整理到与笔记无关的内容
        ApiDtos.NoteView note = noteService.get(id);
        ApiDtos.NoteAssistRequest effective = new ApiDtos.NoteAssistRequest(
                request.action(), note.body(), note.title());
        return ApiResponse.ok(assistService.assist(effective));
    }

    /** 无状态的即时整理：针对编辑器中尚未保存的正文。 */
    @PostMapping("/assist")
    @PreAuthorize("hasAuthority('note:write')")
    public ApiResponse<ApiDtos.NoteAssistResponse> assistDraft(@Valid @RequestBody ApiDtos.NoteAssistRequest request) {
        return ApiResponse.ok(assistService.assist(request));
    }

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    @GetMapping("/{id}/export")
    @PreAuthorize("hasAuthority('note:read')")
    public ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam(defaultValue = "md") String format) {
        NoteExportService.ExportFile file = exportService.export(noteService.get(id), format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    // ------------------------------------------------------------------
    // 分享
    // ------------------------------------------------------------------

    @PostMapping("/{id}/shares")
    @PreAuthorize("hasAuthority('note:write')")
    public ApiResponse<ApiDtos.NoteShareView> createShare(@PathVariable String id,
                                                          @RequestBody(required = false) ApiDtos.CreateNoteShareRequest request) {
        return ApiResponse.ok(shareService.create(id, request));
    }

    @GetMapping("/{id}/shares")
    @PreAuthorize("hasAuthority('note:read')")
    public ApiResponse<List<ApiDtos.NoteShareView>> listShares(@PathVariable String id) {
        return ApiResponse.ok(shareService.list(id));
    }

    @DeleteMapping("/{id}/shares/{shareId}")
    @PreAuthorize("hasAuthority('note:write')")
    public ApiResponse<Void> revokeShare(@PathVariable String id, @PathVariable String shareId) {
        shareService.revoke(id, shareId);
        return ApiResponse.ok(null);
    }

    /**
     * 通过分享令牌只读查看笔记。
     *
     * 要求已登录但不要求特定权限：分享的意义就是让其他用户能看，
     * 但仍保留登录门槛，因为笔记可能含个人研究记录，不做免登录公开链接。
     */
    @GetMapping("/shared/{token}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ApiDtos.SharedNoteView> shared(@PathVariable String token) {
        return ApiResponse.ok(shareService.readByToken(token));
    }
}
