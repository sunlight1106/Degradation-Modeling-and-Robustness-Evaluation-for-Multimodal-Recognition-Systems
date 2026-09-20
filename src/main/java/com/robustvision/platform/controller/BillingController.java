package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.service.BillingService;
import com.robustvision.platform.service.ProviderCredentialService;
import com.robustvision.platform.domain.ModelProvider;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.util.List;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {
    private final BillingService billingService;
    private final ProviderCredentialService credentialService;
    public BillingController(BillingService billingService, ProviderCredentialService credentialService) {
        this.billingService = billingService; this.credentialService = credentialService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('billing:read')")
    public ApiResponse<ApiDtos.BillingSummary> summary() { return ApiResponse.ok(billingService.summary()); }

    @PostMapping("/recharges")
    @PreAuthorize("hasAuthority('billing:recharge')")
    public ApiResponse<ApiDtos.RechargeOrderView> create(@Valid @RequestBody ApiDtos.CreateRechargeRequest request) {
        return ApiResponse.ok(billingService.createRecharge(request));
    }

    @GetMapping("/recharges/{id}")
    @PreAuthorize("hasAuthority('billing:read')")
    public ApiResponse<ApiDtos.RechargeOrderView> recharge(@PathVariable String id) { return ApiResponse.ok(billingService.recharge(id)); }

    @PostMapping("/recharges/{id}/confirm")
    @PreAuthorize("hasAuthority('billing:recharge')")
    public ApiResponse<ApiDtos.RechargeOrderView> confirm(@PathVariable String id, @Valid @RequestBody ApiDtos.ConfirmRechargeRequest request) {
        return ApiResponse.ok(billingService.confirmRecharge(id, request));
    }

    @GetMapping("/providers")
    @PreAuthorize("hasAuthority('billing:read:any')")
    public ApiResponse<List<ApiDtos.ProviderBudgetView>> providers() { return ApiResponse.ok(billingService.providerBudgets()); }

    @PutMapping("/providers/{provider}/budget")
    @PreAuthorize("hasAuthority('billing:manage')")
    public ApiResponse<ApiDtos.ProviderBudgetView> updateBudget(@PathVariable ModelProvider provider,
            @Valid @RequestBody ApiDtos.UpdateProviderBudgetRequest request) {
        return ApiResponse.ok(billingService.updateProviderBudget(provider, request));
    }

    @GetMapping("/admin/wallets")
    @PreAuthorize("hasAuthority('billing:manage')")
    public ApiResponse<List<ApiDtos.AdminWalletView>> wallets() { return ApiResponse.ok(billingService.adminWallets()); }

    @PatchMapping("/admin/wallets/{userId}")
    @PreAuthorize("hasAuthority('billing:manage')")
    public ApiResponse<ApiDtos.AdminWalletView> adjustWallet(@PathVariable Long userId,
            @Valid @RequestBody ApiDtos.AdjustWalletRequest request) {
        return ApiResponse.ok(billingService.adjustWallet(userId, request));
    }

    @GetMapping("/credentials")
    @PreAuthorize("hasAuthority('credential:manage')")
    public ApiResponse<List<ApiDtos.ProviderCredentialView>> credentials() { return ApiResponse.ok(credentialService.list()); }

    @PostMapping("/credentials")
    @PreAuthorize("hasAuthority('credential:manage')")
    public ApiResponse<ApiDtos.ProviderCredentialView> createCredential(@Valid @RequestBody ApiDtos.CreateProviderCredentialRequest request) {
        return ApiResponse.ok(credentialService.create(request));
    }

    @DeleteMapping("/credentials/{id}")
    @PreAuthorize("hasAuthority('credential:manage')")
    public ApiResponse<ApiDtos.ProviderCredentialView> disableCredential(@PathVariable Long id) {
        return ApiResponse.ok(credentialService.disable(id));
    }
}
