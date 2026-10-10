package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.domain.AiProvider;
import com.robustvision.platform.service.PersonalAiDiagnosticsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/personal-ai/settings") @PreAuthorize("hasAuthority('personal-ai:manage') and hasAuthority('personal-ai:use')")
public class PersonalAiDiagnosticsController {
 private final PersonalAiDiagnosticsService service;public PersonalAiDiagnosticsController(PersonalAiDiagnosticsService service){this.service=service;}
 public record Preview(@NotBlank @Pattern(regexp="MODELS|CONNECTION") String mode){}
 public record Execute(@NotBlank @Size(max=100) String token,boolean confirmed){}
 @PostMapping("/{provider}/diagnostics/preview") Object preview(@PathVariable AiProvider provider,@Valid @RequestBody Preview r){return ApiResponse.ok(service.preview(provider,r.mode()));}
 @PostMapping("/diagnostics/execute") Object execute(@Valid @RequestBody Execute r){return ApiResponse.ok(service.execute(r.token(),r.confirmed()));}
}
