package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.GroupConversationService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/workspaces") @PreAuthorize("hasAuthority('group:use')")
public class GroupConversationController {
    private final GroupConversationService service;
    public GroupConversationController(GroupConversationService service){this.service=service;}
    @GetMapping("/overview") public Object overview(){return ApiResponse.ok(service.overview());}
    @GetMapping("/{id}/features") public Object features(@PathVariable long id){return ApiResponse.ok(service.get(id));}
    @PutMapping("/{id}/preferences") public Object preferences(@PathVariable long id,@Valid @RequestBody GroupConversationService.Preferences input){return ApiResponse.ok(service.preferences(id,input));}
    @PostMapping("/{id}/read") @PreAuthorize("hasAuthority('group:use') and hasAuthority('message:read')") public Object read(@PathVariable long id,@Valid @RequestBody GroupConversationService.Read input){return ApiResponse.ok(service.read(id,input));}
    @PutMapping("/{id}/announcement") @PreAuthorize("hasAuthority('group:use') and hasAuthority('message:read')") public Object announcement(@PathVariable long id,@Valid @RequestBody GroupConversationService.Announcement input){return ApiResponse.ok(service.announcement(id,input));}
    @PutMapping("/{id}/pin") @PreAuthorize("hasAuthority('group:use') and hasAuthority('message:read')") public Object pin(@PathVariable long id,@Valid @RequestBody GroupConversationService.Pin input){return ApiResponse.ok(service.pin(id,input));}
    @GetMapping("/{id}/people") public Object people(@PathVariable long id,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page){return ApiResponse.ok(service.directory(id,q,page));}
    @GetMapping("/{id}/search") @PreAuthorize("hasAuthority('group:use') and hasAuthority('message:read')") public Object search(@PathVariable long id,@RequestParam String q,@RequestParam(defaultValue="0") int page){return ApiResponse.ok(service.search(id,q,page));}
}
