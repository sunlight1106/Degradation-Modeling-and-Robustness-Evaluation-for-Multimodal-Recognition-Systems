package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.AccountSecurityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class AccountSecurityController {
    private final AccountSecurityService service;
    public AccountSecurityController(AccountSecurityService service) { this.service=service; }
    public record Password(@NotBlank @Size(max=100) String password) {}
    public record Proof(@NotBlank @Size(max=100) String password,@NotBlank @Size(max=32) String code) {}
    public record Email(@NotBlank @jakarta.validation.constraints.Email @Size(max=160) String email) {}
    public record Link(@NotBlank @Size(max=100) String token,@Size(max=72) String password) {}
    @GetMapping("/account/security") Object state() { return ApiResponse.ok(service.state()); }
    @PostMapping("/account/security/email") Object verify(@Valid @RequestBody Password r) { service.requestVerification(r.password()); return ApiResponse.ok(null); }
    @PostMapping("/account/security/mfa/setup") Object setup(@Valid @RequestBody Password r) { return ApiResponse.ok(service.setup(r.password())); }
    @PostMapping("/account/security/mfa/enable") Object enable(@Valid @RequestBody Proof r) { return ApiResponse.ok(service.enable(r.password(),r.code())); }
    @PostMapping("/account/security/mfa/disable") Object disable(@Valid @RequestBody Proof r) { service.disable(r.password(),r.code()); return ApiResponse.ok(null); }
    @PostMapping("/auth/forgot-password") Object forgot(@Valid @RequestBody Email r) { service.forgot(r.email()); return ApiResponse.ok(null); }
    @PostMapping("/auth/reset-password") Object reset(@Valid @RequestBody Link r) { service.consume(r.token(),"RESET",r.password()); return ApiResponse.ok(null); }
    @PostMapping("/auth/verify-email") Object consume(@Valid @RequestBody Link r) { service.consume(r.token(),"VERIFY",null); return ApiResponse.ok(null); }
 @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
 @PostMapping("/account/bindings/email") public Object binding(@Valid @RequestBody Binding request){service.requestBinding(request.password(),request.otp(),request.email());return com.robustvision.platform.common.ApiResponse.ok(null);}
 public record Binding(@NotBlank @Size(max=100) String password,@Size(max=32) String otp,@jakarta.validation.constraints.Email @NotBlank @Size(max=160) String email) {}
}
