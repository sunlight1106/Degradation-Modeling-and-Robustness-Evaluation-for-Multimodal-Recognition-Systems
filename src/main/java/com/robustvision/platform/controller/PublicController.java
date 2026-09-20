package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.service.ModelService;
import com.robustvision.platform.service.BillingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/public")
public class PublicController {
    private final ModelService modelService;
    private final BillingService billingService;
    public PublicController(ModelService modelService, BillingService billingService) { this.modelService = modelService; this.billingService = billingService; }

    @GetMapping("/models")
    public ApiResponse<List<ApiDtos.ModelView>> models() { return ApiResponse.ok(modelService.listActive()); }

    @GetMapping("/payments/{token}")
    public ApiResponse<ApiDtos.PublicPaymentView> payment(@PathVariable String token) { return ApiResponse.ok(billingService.publicPayment(token)); }

    @PostMapping("/payments/{token}/complete")
    public ApiResponse<ApiDtos.PublicPaymentView> completePayment(@PathVariable String token) { return ApiResponse.ok(billingService.completeQrPayment(token)); }
}
