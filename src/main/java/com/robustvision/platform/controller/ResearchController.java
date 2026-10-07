package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.PersonalAiDtos;
import com.robustvision.platform.service.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController @RequestMapping("/api/v1/research") @PreAuthorize("isAuthenticated()")
public class ResearchController {
    private final KnowledgeSearchService search;private final KnowledgeAnswerService answers;private final LearningWorkspaceService learning;
    public ResearchController(KnowledgeSearchService search,KnowledgeAnswerService answers,LearningWorkspaceService learning){this.search=search;this.answers=answers;this.learning=learning;}
    @GetMapping("/search") public Object search(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="ALL") String type,@RequestParam(defaultValue="") String tag,@RequestParam(required=false) Instant since,@RequestParam(required=false) Long group,@RequestParam(defaultValue="0") int page){return ApiResponse.ok(search.search(q,type,tag,since,group,page));}
    @GetMapping("/sources/{kind}/{id}") public Object source(@PathVariable String kind,@PathVariable String id){return ApiResponse.ok(search.source(kind,id));}
    @PostMapping("/answers/preview") @PreAuthorize("hasAuthority('note:write')") public Object preview(@Valid @RequestBody KnowledgeAnswerService.Question body){return ApiResponse.ok(answers.preview(body));}
    @PostMapping("/answers/execute") @PreAuthorize("hasAuthority('note:write')") public Object execute(@Valid @RequestBody PersonalAiDtos.ExecuteRequest body){return ApiResponse.ok(answers.execute(body));}
    @GetMapping("/records") public Object records(@RequestParam String kind,@RequestParam(defaultValue="0") int page){return ApiResponse.ok(learning.list(kind,page));}
    @GetMapping("/records/{id}") public Object record(@PathVariable String id){return ApiResponse.ok(learning.get(id));}
    @PostMapping("/records/{kind}") public Object create(@PathVariable String kind,@Valid @RequestBody LearningWorkspaceService.Save body){return ApiResponse.ok(learning.save(kind,null,body));}
    @PutMapping("/records/{kind}/{id}") public Object update(@PathVariable String kind,@PathVariable String id,@Valid @RequestBody LearningWorkspaceService.Save body){return ApiResponse.ok(learning.save(kind,id,body));}
    @DeleteMapping("/records/{id}") public Object delete(@PathVariable String id,@RequestParam long revision){learning.delete(id,revision);return ApiResponse.ok(null);}
    @PostMapping("/records/{id}/grade") public Object grade(@PathVariable String id,@Valid @RequestBody LearningWorkspaceService.Grade body){return ApiResponse.ok(learning.grade(id,body));}
    @PostMapping("/capture") @PreAuthorize("hasAuthority('note:write')") public Object capture(@Valid @RequestBody LearningWorkspaceService.Capture body){return ApiResponse.ok(learning.capture(body));}
}
