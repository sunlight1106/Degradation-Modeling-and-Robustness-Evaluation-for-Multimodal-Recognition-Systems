package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.service.KnowledgeService;
import jakarta.validation.Valid;
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

import java.util.List;

/** 知识库接口：跨学科主题与知识卡。读需 knowledge:read，写需 knowledge:write。 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeService service;

    public KnowledgeController(KnowledgeService service) {
        this.service = service;
    }

    @GetMapping("/topics")
    @PreAuthorize("hasAuthority('knowledge:read')")
    public ApiResponse<List<ApiDtos.KnowledgeTopicView>> topics() {
        return ApiResponse.ok(service.listTopics());
    }

    @GetMapping("/domains")
    @PreAuthorize("hasAuthority('knowledge:read')")
    public ApiResponse<List<String>> domains() {
        return ApiResponse.ok(service.listDomains());
    }

    @PostMapping("/topics")
    @PreAuthorize("hasAuthority('knowledge:write')")
    public ApiResponse<ApiDtos.KnowledgeTopicView> createTopic(@Valid @RequestBody ApiDtos.CreateKnowledgeTopicRequest request) {
        return ApiResponse.ok(service.createTopic(request));
    }

    @PatchMapping("/topics/{id}")
    @PreAuthorize("hasAuthority('knowledge:write')")
    public ApiResponse<ApiDtos.KnowledgeTopicView> updateTopic(@PathVariable Long id,
                                                              @Valid @RequestBody ApiDtos.UpdateKnowledgeTopicRequest request) {
        return ApiResponse.ok(service.updateTopic(id, request));
    }

    @DeleteMapping("/topics/{id}")
    @PreAuthorize("hasAuthority('knowledge:write')")
    public ApiResponse<Void> deleteTopic(@PathVariable Long id) {
        service.deleteTopic(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/entries")
    @PreAuthorize("hasAuthority('knowledge:read')")
    public ApiResponse<List<ApiDtos.KnowledgeEntryView>> entries(
            @RequestParam(required = false) Long topicId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(service.listEntries(topicId, keyword));
    }

    @GetMapping("/entries/{id}")
    @PreAuthorize("hasAuthority('knowledge:read')")
    public ApiResponse<ApiDtos.KnowledgeEntryView> entry(@PathVariable String id) {
        return ApiResponse.ok(service.entry(id));
    }

    @PostMapping("/entries")
    @PreAuthorize("hasAuthority('knowledge:write')")
    public ApiResponse<ApiDtos.KnowledgeEntryView> createEntry(@Valid @RequestBody ApiDtos.CreateKnowledgeEntryRequest request) {
        return ApiResponse.ok(service.createEntry(request));
    }

    @PatchMapping("/entries/{id}")
    @PreAuthorize("hasAuthority('knowledge:write')")
    public ApiResponse<ApiDtos.KnowledgeEntryView> updateEntry(@PathVariable String id,
                                                              @Valid @RequestBody ApiDtos.UpdateKnowledgeEntryRequest request) {
        return ApiResponse.ok(service.updateEntry(id, request));
    }

    @DeleteMapping("/entries/{id}")
    @PreAuthorize("hasAuthority('knowledge:write')")
    public ApiResponse<Void> deleteEntry(@PathVariable String id) {
        service.deleteEntry(id);
        return ApiResponse.ok(null);
    }
}
