package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.GroupLifecycleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
@RestController @RequestMapping("/api/v1/workspaces")
public class GroupLifecycleController {
 private final GroupLifecycleService service;public GroupLifecycleController(GroupLifecycleService service){this.service=service;}
 public record State(boolean archived,boolean acceptRequests){}
 public record Invite(@Positive long targetId,@NotBlank @Size(max=12) String role){}
 public record Decision(boolean accept){}
 public record Transfer(@Positive long targetId,@NotBlank @Size(max=100) String password){}
 public record Dissolve(@NotBlank @Size(max=100) String name,@NotBlank @Size(max=100) String password){}
 public record Report(@NotBlank @Size(max=500) String reason){}
 public record Resolution(@NotBlank @Size(max=12) String status){}
 @GetMapping("/{id}/lifecycle") Object state(@PathVariable long id){return ApiResponse.ok(service.state(id));}
 @PutMapping("/{id}/lifecycle") Object state(@PathVariable long id,@RequestBody State r){service.state(id,r.archived(),r.acceptRequests());return ApiResponse.ok(null);}
 @PostMapping("/{id}/transfer") Object transfer(@PathVariable long id,@Valid @RequestBody Transfer r){service.transfer(id,r.targetId(),r.password());return ApiResponse.ok(null);}
 @PostMapping("/{id}/dissolve") Object dissolve(@PathVariable long id,@Valid @RequestBody Dissolve r){service.dissolve(id,r.name(),r.password());return ApiResponse.ok(null);}
 @PostMapping("/{id}/invitations") Object invite(@PathVariable long id,@Valid @RequestBody Invite r){service.invite(id,r.targetId(),r.role());return ApiResponse.ok(null);}
 @GetMapping("/invitations") Object invitations(){return ApiResponse.ok(service.invitations(null));}
 @GetMapping("/{id}/invitations") Object pending(@PathVariable long id){return ApiResponse.ok(service.invitations(id));}
 @PostMapping("/invitations/{invitation}/decision") Object decide(@PathVariable String invitation,@RequestBody Decision r){service.decide(invitation,r.accept());return ApiResponse.ok(null);}
 @PostMapping("/{id}/join-request") Object join(@PathVariable long id){service.request(id);return ApiResponse.ok(null);}
 @PostMapping("/{id}/messages/{message}/recall") @PreAuthorize("hasAuthority('message:read')") Object recall(@PathVariable long id,@PathVariable String message){service.recall(id,message);return ApiResponse.ok(null);}
 @PostMapping("/{id}/messages/{message}/report") @PreAuthorize("hasAuthority('message:read')") Object report(@PathVariable long id,@PathVariable String message,@Valid @RequestBody Report r){service.report(id,message,r.reason());return ApiResponse.ok(null);}
 @GetMapping("/{id}/reports") @PreAuthorize("hasAuthority('message:read')") Object reports(@PathVariable long id){return ApiResponse.ok(service.reports(id));}
 @PostMapping("/{id}/reports/{report}/resolve") @PreAuthorize("hasAuthority('message:read')") Object resolve(@PathVariable long id,@PathVariable String report,@Valid @RequestBody Resolution r){service.resolve(id,report,r.status());return ApiResponse.ok(null);}
}
