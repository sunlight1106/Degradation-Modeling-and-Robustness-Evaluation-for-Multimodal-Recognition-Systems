package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.service.AccountSwitchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/account/switch")
@PreAuthorize("isAuthenticated()")
public class AccountSwitchController {
    private final AccountSwitchService service;
    public AccountSwitchController(AccountSwitchService service) { this.service = service; }
    @PostMapping
    public ApiResponse<ApiDtos.LoginResponse> change(@Valid @RequestBody ApiDtos.LoginRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.switchAccount(request, http.getHeader("User-Agent")));
    }
}
