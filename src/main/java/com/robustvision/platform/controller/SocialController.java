package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.SocialDtos;
import com.robustvision.platform.service.SocialService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/v1/social") @PreAuthorize("isAuthenticated()")
public class SocialController {
    private final SocialService service;
    public SocialController(SocialService service) { this.service = service; }
    @GetMapping("/settings") public ApiResponse<SocialDtos.Discoverability> settings() { return ApiResponse.ok(service.settings()); }
    @PutMapping("/settings") public ApiResponse<SocialDtos.Discoverability> settings(@Valid @RequestBody SocialDtos.Discoverability body) { return ApiResponse.ok(service.settings(body.discoverable())); }
    @GetMapping("/people") public ApiResponse<List<SocialDtos.Person>> search(@RequestParam String q) { return ApiResponse.ok(service.search(q)); }
    @GetMapping("/contacts") public ApiResponse<List<SocialDtos.Contact>> list() { return ApiResponse.ok(service.list()); }
    @PostMapping("/contacts") public ApiResponse<Void> request(@Valid @RequestBody SocialDtos.ContactRequest request) { service.request(request.userId()); return ApiResponse.ok(null); }
    @PatchMapping("/contacts/{id}") public ApiResponse<Void> act(@PathVariable long id, @Valid @RequestBody SocialDtos.Action body) { service.act(id, body.action()); return ApiResponse.ok(null); }
    @PatchMapping("/contacts/{id}/preferences") public ApiResponse<Void> preferences(@PathVariable long id, @Valid @RequestBody SocialDtos.Preferences body) { service.preferences(id, body); return ApiResponse.ok(null); }
    @PostMapping("/contacts/{id}/read") public ApiResponse<Void> read(@PathVariable long id, @Valid @RequestBody SocialDtos.ReadReceipt body) { service.read(id, body.through()); return ApiResponse.ok(null); }
    @PostMapping("/contacts/{id}/clear-history") public ApiResponse<Void> clearHistory(@PathVariable long id) { service.clearHistory(id); return ApiResponse.ok(null); }
    @GetMapping("/contacts/{id}/messages") public ApiResponse<List<SocialDtos.ChatMessage>> messages(@PathVariable long id, @RequestParam(required = false) Long after, @RequestParam(required = false) Long before) { return ApiResponse.ok(service.messages(id, after, before)); }
    @PostMapping("/contacts/{id}/messages") public ApiResponse<SocialDtos.ChatMessage> send(@PathVariable long id, @Valid @RequestBody SocialDtos.ChatRequest body) { return ApiResponse.ok(service.send(id, body)); }
}
