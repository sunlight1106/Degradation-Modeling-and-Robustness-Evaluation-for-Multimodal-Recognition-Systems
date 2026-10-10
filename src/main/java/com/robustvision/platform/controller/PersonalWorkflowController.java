package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.PersonalWorkflowService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1") @PreAuthorize("isAuthenticated()")
public class PersonalWorkflowController {
    private final PersonalWorkflowService service;
    public PersonalWorkflowController(PersonalWorkflowService service){this.service=service;}
    @GetMapping("/account/preferences") Object get(){return ApiResponse.ok(service.get());}
    @PutMapping("/account/preferences") Object save(@Valid @RequestBody PersonalWorkflowService.Preferences p){return ApiResponse.ok(service.save(p));}
    @GetMapping("/notes/reminders") @PreAuthorize("hasAuthority('note:read')") Object due(){return ApiResponse.ok(service.due());}
    @GetMapping("/notes/{id}/reminder") @PreAuthorize("hasAuthority('note:read')") Object reminder(@PathVariable String id){return ApiResponse.ok(service.reminder(id));}
    @PutMapping("/notes/{id}/reminder") @PreAuthorize("hasAuthority('note:write')") Object reminder(@PathVariable String id,@Valid @RequestBody PersonalWorkflowService.Reminder p){service.remind(id,p);return ApiResponse.ok(null);}
    @PostMapping("/notes/{id}/reminder/complete") @PreAuthorize("hasAuthority('note:write')") Object complete(@PathVariable String id){service.finish(id);return ApiResponse.ok(null);}
    @DeleteMapping("/notes/{id}/reminder") @PreAuthorize("hasAuthority('note:write')") Object clear(@PathVariable String id){service.clear(id);return ApiResponse.ok(null);}
}
