package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.PersonalAiMemoryService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/v1/personal-ai/memories") @PreAuthorize("isAuthenticated()")
public class PersonalAiMemoryController {
    private final PersonalAiMemoryService service;
    public PersonalAiMemoryController(PersonalAiMemoryService service){this.service=service;}
    @GetMapping public ApiResponse<List<PersonalAiMemoryService.View>> list(){return ApiResponse.ok(service.list());}
    @PostMapping public ApiResponse<PersonalAiMemoryService.View> create(@Valid @RequestBody PersonalAiMemoryService.Request request){return ApiResponse.ok(service.save(null,request));}
    @PutMapping("/{id}") public ApiResponse<PersonalAiMemoryService.View> update(@PathVariable String id,@Valid @RequestBody PersonalAiMemoryService.Request request){return ApiResponse.ok(service.save(id,request));}
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable String id,@RequestParam long revision){service.delete(id,revision);return ApiResponse.ok(null);}
}
