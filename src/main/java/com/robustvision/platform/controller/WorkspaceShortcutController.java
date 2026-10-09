package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/research") @PreAuthorize("hasAuthority('research:use')")
public class WorkspaceShortcutController {
    private final WorkspaceShortcutService shortcuts;private final KnowledgeSearchService search;
    public WorkspaceShortcutController(WorkspaceShortcutService shortcuts,KnowledgeSearchService search){this.shortcuts=shortcuts;this.search=search;}
    @GetMapping("/bookmarks") public Object bookmarks(@RequestParam(defaultValue="0") int page){return ApiResponse.ok(search.bookmarks(page));}
    @GetMapping("/bookmarks/keys") public Object keys(){return ApiResponse.ok(shortcuts.bookmarkKeys());}
    @PostMapping("/bookmarks/cleanup") public Object cleanup(){return ApiResponse.ok(shortcuts.cleanupBookmarks());}
    @PutMapping("/bookmarks") public Object bookmark(@Valid @RequestBody WorkspaceShortcutService.Reference body){shortcuts.bookmark(body);return ApiResponse.ok(null);}
    @DeleteMapping("/bookmarks/{kind}/{id}") public Object removeBookmark(@PathVariable String kind,@PathVariable String id){shortcuts.removeBookmark(kind,id);return ApiResponse.ok(null);}
    @GetMapping("/saved-searches") public Object saved(){return ApiResponse.ok(shortcuts.savedSearches());}
    @PostMapping("/saved-searches") public Object save(@Valid @RequestBody WorkspaceShortcutService.SaveSearch body){return ApiResponse.ok(shortcuts.saveSearch(body));}
    @DeleteMapping("/saved-searches/{id}") public Object remove(@PathVariable String id){shortcuts.removeSearch(id);return ApiResponse.ok(null);}
}
