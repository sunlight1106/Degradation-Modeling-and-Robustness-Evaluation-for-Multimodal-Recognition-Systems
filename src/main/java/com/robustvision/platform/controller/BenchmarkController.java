package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.BenchmarkService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/research/evaluations") @PreAuthorize("hasAuthority('experiment:read') or hasAuthority('experiment:read:any')")
public class BenchmarkController {
    private final BenchmarkService service;public BenchmarkController(BenchmarkService service){this.service=service;}
    @GetMapping public Object list(){return ApiResponse.ok(service.list());}
    @PostMapping public Object evaluate(@Valid @RequestBody BenchmarkService.Evaluation body){return ApiResponse.ok(service.evaluate(body));}
}
