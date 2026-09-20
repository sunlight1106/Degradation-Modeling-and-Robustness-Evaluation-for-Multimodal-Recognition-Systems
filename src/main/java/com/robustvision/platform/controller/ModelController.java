package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.service.ModelInvocationService;
import com.robustvision.platform.service.ModelService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/models")
public class ModelController {
    private final ModelService modelService;
    private final ModelInvocationService invocationService;

    public ModelController(ModelService modelService, ModelInvocationService invocationService) {
        this.modelService = modelService;
        this.invocationService = invocationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('model:read')")
    public ApiResponse<List<ApiDtos.ModelView>> list() {
        return ApiResponse.ok(modelService.listActive());
    }

    @GetMapping("/runtime")
    @PreAuthorize("hasAuthority('model:read')")
    public ApiResponse<ModelInvocationService.RuntimeInfo> runtime() {
        return ApiResponse.ok(invocationService.runtimeInfo());
    }
}
