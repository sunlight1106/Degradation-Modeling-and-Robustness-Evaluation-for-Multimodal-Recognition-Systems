package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.service.VocabularySkillsService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/vocabulary/statistics")
public class VocabularySkillsController {
 private final VocabularySkillsService service;public VocabularySkillsController(VocabularySkillsService service){this.service=service;}
 @GetMapping Object stats(@RequestParam(defaultValue="30") int days){return ApiResponse.ok(service.stats(days));}
}
