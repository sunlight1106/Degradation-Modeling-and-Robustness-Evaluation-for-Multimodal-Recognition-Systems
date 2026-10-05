package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.AccountUsageService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/account/usage")
public class AccountUsageController {
    private final AccountUsageService usage;
    public AccountUsageController(AccountUsageService usage){this.usage=usage;}
    @GetMapping public ApiResponse<AccountUsageService.Summary> summary(){return ApiResponse.ok(usage.summary());}
}
