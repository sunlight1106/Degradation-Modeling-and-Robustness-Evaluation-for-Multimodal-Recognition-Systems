package com.robustvision.platform.controller;

import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.dto.VocabularyDtos.*;
import com.robustvision.platform.service.VocabularyService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vocabulary")
@PreAuthorize("isAuthenticated()")
public class VocabularyController {
    private final VocabularyService service;
    public VocabularyController(VocabularyService service) {this.service=service;}
    public record Collect(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=80) String term) {}
    @PostMapping("/collect") public Object collect(@Valid @RequestBody Collect input){return ApiResponse.ok(service.collectTerm(input.term()));}
    @GetMapping("/dashboard") public ApiResponse<Dashboard> dashboard(){return ApiResponse.ok(service.dashboard());}
    @PutMapping("/settings") public ApiResponse<Settings> settings(@Valid @RequestBody SettingsRequest input){return ApiResponse.ok(service.updateSettings(input));}
    @PostMapping("/next") public ApiResponse<Next> next(@Valid @RequestBody NextRequest input){return ApiResponse.ok(service.next(input));}
    @PostMapping("/questions/{id}/answer") public ApiResponse<Answer> answer(@PathVariable String id,@Valid @RequestBody AnswerRequest input){return ApiResponse.ok(service.answer(id,input));}
    @GetMapping("/books/{id}/words") public ApiResponse<WordPage> words(@PathVariable String id,@RequestParam(defaultValue="ALL") String filter,@RequestParam(required=false) String query,@RequestParam(defaultValue="0") int page){return ApiResponse.ok(service.browse(id,filter,query,page));}
    @PutMapping("/words/{id}/star") public ApiResponse<Word> star(@PathVariable String id,@RequestBody StarRequest input){return ApiResponse.ok(service.star(id,input.starred()));}
    @PostMapping("/books/import") public ApiResponse<Book> importBook(@Valid @RequestBody ImportRequest input){return ApiResponse.ok(service.importBook(input));}
}
