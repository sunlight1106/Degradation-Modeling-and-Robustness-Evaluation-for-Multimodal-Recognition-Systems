package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.NoteOrganizeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/notes/batch") @PreAuthorize("hasAuthority('note:write')")
public class NoteOrganizeController {
    private final NoteOrganizeService service;
    public NoteOrganizeController(NoteOrganizeService service){this.service=service;}
    @PostMapping public Object apply(@Valid @RequestBody NoteOrganizeService.Batch request){return ApiResponse.ok(service.apply(request));}
}
