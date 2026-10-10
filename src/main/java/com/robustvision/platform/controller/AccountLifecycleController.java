package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/account") @PreAuthorize("isAuthenticated()")
public class AccountLifecycleController {
    private final AccountLifecycleService lifecycle;private final AccountActivityService activity;
    public AccountLifecycleController(AccountLifecycleService lifecycle,AccountActivityService activity){this.lifecycle=lifecycle;this.activity=activity;}
    @GetMapping("/activity") Object log(@RequestParam(defaultValue="0") int page){return ApiResponse.ok(activity.own(page));}
    @PostMapping("/closure/prepare") Object prepare(@Valid @RequestBody AccountLifecycleService.Prepare request){return ApiResponse.ok(lifecycle.prepare(request));}
    @PostMapping("/closure/confirm") Object confirm(@Valid @RequestBody AccountLifecycleService.Confirm request){lifecycle.confirm(request);return ApiResponse.ok(null);}
}
