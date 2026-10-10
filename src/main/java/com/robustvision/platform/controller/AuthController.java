package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.service.AuthService;
import com.robustvision.platform.service.UserService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    @org.springframework.beans.factory.annotation.Autowired private com.robustvision.platform.service.AccountActivityService activity;
    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/login")
    public ApiResponse<ApiDtos.LoginResponse> login(@Valid @RequestBody ApiDtos.LoginRequest request, HttpServletRequest httpRequest) {
        try {var result=authService.login(request,httpRequest.getHeader("User-Agent")); activity.record(request.username(),"LOGIN","SUCCESS",httpRequest);return ApiResponse.ok(result);}
        catch(com.robustvision.platform.common.BusinessException e){activity.record(request.username(),"LOGIN",e.getCode(),httpRequest);throw e;}
    }

    @PostMapping("/reactivate")
    public ApiResponse<ApiDtos.LoginResponse> reactivate(@Valid @RequestBody ApiDtos.LoginRequest request,HttpServletRequest httpRequest) {
        try{var result=authService.reactivate(request,httpRequest.getHeader("User-Agent"));activity.record(request.username(),"REACTIVATE","SUCCESS",httpRequest);return ApiResponse.ok(result);}
        catch(com.robustvision.platform.common.BusinessException e){activity.record(request.username(),"REACTIVATE",e.getCode(),httpRequest);throw e;}
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
