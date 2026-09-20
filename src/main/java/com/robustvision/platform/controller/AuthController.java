package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.service.AuthService;
import com.robustvision.platform.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/login")
    public ApiResponse<ApiDtos.LoginResponse> login(@Valid @RequestBody ApiDtos.LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/register")
    public ApiResponse<ApiDtos.UserView> register(@Valid @RequestBody ApiDtos.RegisterRequest request) {
        return ApiResponse.ok(userService.register(request));
    }

    @GetMapping("/me")
    public ApiResponse<ApiDtos.UserView> me() {
        return ApiResponse.ok(userService.currentUser());
    }
}
