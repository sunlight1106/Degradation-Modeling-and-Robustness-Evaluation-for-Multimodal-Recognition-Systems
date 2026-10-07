package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.NoteHistoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/notes") @PreAuthorize("hasAuthority('note:read')")
public class NoteHistoryController {
    private final NoteHistoryService service;
    public NoteHistoryController(NoteHistoryService service){this.service=service;}
    public record Restore(@Min(0) long baseRevision) {}
    @GetMapping("/{id}/versions") public Object list(@PathVariable String id,@RequestParam(defaultValue="0") int page){return ApiResponse.ok(service.list(id,page));}
    @GetMapping("/{id}/versions/{version}") public Object detail(@PathVariable String id,@PathVariable String version){return ApiResponse.ok(service.detail(id,version));}
    @PostMapping("/{id}/versions/{version}/restore") @PreAuthorize("hasAuthority('note:write')")
    public Object restore(@PathVariable String id,@PathVariable String version,@Valid @RequestBody Restore request){service.restoreVersion(id,version,request.baseRevision());return ApiResponse.ok(null);}
    @GetMapping("/trash") public Object trash(@RequestParam(defaultValue="0") int page){return ApiResponse.ok(service.trash(page));}
    @PostMapping("/{id}/restore") @PreAuthorize("hasAuthority('note:write')") public Object restore(@PathVariable String id){service.restoreDeleted(id);return ApiResponse.ok(null);}
    @DeleteMapping("/{id}/purge") @PreAuthorize("hasAuthority('note:write')") public Object purge(@PathVariable String id){service.purge(id);return ApiResponse.ok(null);}
}
