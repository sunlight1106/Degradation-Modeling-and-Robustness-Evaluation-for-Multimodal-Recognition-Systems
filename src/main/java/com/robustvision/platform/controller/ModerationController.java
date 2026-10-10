package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.ModerationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/moderation") @PreAuthorize("isAuthenticated()")
public class ModerationController {
 private final ModerationService service;
 public ModerationController(ModerationService service){this.service=service;}
 public record Report(@NotBlank String type,@NotBlank @Size(max=36) String sourceId,@NotBlank @Size(max=500) String reason){}
 public record Review(@NotBlank String decision,@NotBlank @Size(max=1000) String reason,Integer minutes,Set<String> features){}
 public record Reason(@NotBlank @Size(max=1000) String reason){}
 public record AppealDecision(boolean accept,@NotBlank @Size(max=1000) String reason){}
 @PostMapping("/reports") public Object report(@Valid @RequestBody Report r){return ApiResponse.ok(Map.of("id",service.report(r.type(),r.sourceId(),r.reason())));}
 @GetMapping("/mine") public Object mine(){return ApiResponse.ok(service.mine());}
 @PostMapping("/penalties/{id}/appeal") public Object appeal(@PathVariable String id,@Valid @RequestBody Reason r){service.appeal(id,r.reason());return ApiResponse.ok(null);}
 @GetMapping("/reports") @PreAuthorize("hasAuthority('moderation:review')") public Object queue(@RequestParam(defaultValue="PENDING") String status,@RequestParam(defaultValue="0") int page){return ApiResponse.ok(service.queue(status,page));}
 @GetMapping("/reports/{id}") @PreAuthorize("hasAuthority('moderation:review')") public Object detail(@PathVariable String id){return ApiResponse.ok(service.detail(id));}
 @PostMapping("/reports/{id}/review") @PreAuthorize("hasAuthority('moderation:review')") public Object review(@PathVariable String id,@Valid @RequestBody Review r){service.review(id,r.decision(),r.reason(),r.minutes(),r.features());return ApiResponse.ok(null);}
 @PostMapping("/penalties/{id}/revoke") @PreAuthorize("hasAuthority('moderation:review')") public Object revoke(@PathVariable String id,@Valid @RequestBody Reason r){service.revoke(id,r.reason());return ApiResponse.ok(null);}
 @PostMapping("/penalties/{id}/decision") @PreAuthorize("hasAuthority('moderation:review')") public Object decision(@PathVariable String id,@Valid @RequestBody AppealDecision r){service.appealDecision(id,r.accept(),r.reason());return ApiResponse.ok(null);}
}
