package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.domain.AiProvider;
import com.robustvision.platform.dto.PersonalAiDtos.*;
import com.robustvision.platform.service.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/personal-ai")
@PreAuthorize("isAuthenticated()")
public class PersonalAiController {
    private final PersonalAiSettingsService settings;
    private final PersonalAiService ai;
    public PersonalAiController(PersonalAiSettingsService settings, PersonalAiService ai) { this.settings = settings; this.ai = ai; }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> malformedRequest() {
        // Never forward Jackson exception text: it can contain a credential-bearing source fragment.
        return ResponseEntity.badRequest().body(ApiResponse.failure("PERSONAL_AI_REQUEST_INVALID", "请求格式无效"));
    }
    @GetMapping("/providers") public ApiResponse<List<ProviderView>> providers() { return ApiResponse.ok(settings.providers()); }
    @GetMapping("/settings") public ApiResponse<List<SettingView>> settings() { return ApiResponse.ok(settings.list()); }
    @PutMapping("/settings/{provider}") public ApiResponse<SettingView> save(@PathVariable AiProvider provider, @Valid @RequestBody SettingRequest request) {
        return ApiResponse.ok(settings.save(provider, request));
    }
    @DeleteMapping("/settings/{provider}") public ApiResponse<Void> delete(@PathVariable AiProvider provider) { settings.delete(provider); return ApiResponse.ok(null); }
    @GetMapping("/usage") public ApiResponse<List<UsageView>> usage() { return ApiResponse.ok(settings.usage()); }
    @PostMapping("/preview") @PreAuthorize("hasAuthority('note:write')")
    public ApiResponse<PreviewView> preview(@Valid @RequestBody PreviewRequest request) { return ApiResponse.ok(ai.preview(request)); }
    @PostMapping("/execute") @PreAuthorize("hasAuthority('note:write')")
    public ApiResponse<ResultView> execute(@Valid @RequestBody ExecuteRequest request) { return ApiResponse.ok(ai.execute(request)); }
}
