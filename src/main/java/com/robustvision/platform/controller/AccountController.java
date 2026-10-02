package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.*;
import com.robustvision.platform.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {
    private final AccountService account;
    public AccountController(AccountService account) { this.account = account; }
    @GetMapping("/profile")
    public ApiResponse<ApiDtos.UserView> profile() { return ApiResponse.ok(account.profile()); }
    @PatchMapping("/profile")
    public ApiResponse<ApiDtos.UserView> update(@Valid @RequestBody AccountDtos.ProfileUpdateRequest request) {
        return ApiResponse.ok(account.updateProfile(request));
    }
    @PostMapping("/password")
    public ApiResponse<Void> password(@Valid @RequestBody AccountDtos.PasswordChangeRequest request) {
        account.changePassword(request); return ApiResponse.ok(null);
    }
    @GetMapping("/sessions")
    public ApiResponse<List<AccountDtos.SessionView>> sessions() { return ApiResponse.ok(account.listSessions()); }
    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> revoke(@PathVariable String id, @Valid @RequestBody AccountDtos.PasswordConfirmationRequest request) {
        account.revokeSession(id, request); return ApiResponse.ok(null);
    }
    @PostMapping("/sessions/revoke-others")
    public ApiResponse<Void> revokeOthers(@Valid @RequestBody AccountDtos.PasswordConfirmationRequest request) {
        account.revokeOthers(request); return ApiResponse.ok(null);
    }
    @PostMapping("/logout")
    public ApiResponse<Void> logout() { account.logout(); return ApiResponse.ok(null); }
    @PostMapping("/export")
    public ApiResponse<Map<String, Object>> export(@Valid @RequestBody AccountDtos.PasswordConfirmationRequest request) {
        return ApiResponse.ok(account.export(request));
    }
}
