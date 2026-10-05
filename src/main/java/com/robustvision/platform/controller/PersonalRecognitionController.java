package com.robustvision.platform.controller;
import com.robustvision.platform.common.ApiResponse;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.service.PersonalRecognitionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1/personal-ai/recognition")
@PreAuthorize("hasAuthority('experiment:run')")
public class PersonalRecognitionController {
    private final PersonalRecognitionService service;
    public PersonalRecognitionController(PersonalRecognitionService service){this.service=service;}
    @PostMapping("/preview") public ApiResponse<PersonalRecognitionService.Preview> preview(@Valid @RequestBody PreviewRequest request){return ApiResponse.ok(service.preview(request.provider(),request.fileId(),request.taskType(),request.question()));}
    @PostMapping("/execute") public ApiResponse<PersonalRecognitionService.Result> execute(@Valid @RequestBody ExecuteRequest request){return ApiResponse.ok(service.execute(request.previewToken(),request.confirmed()));}
    @GetMapping("/results") public ApiResponse<List<PersonalRecognitionService.Result>> results(){return ApiResponse.ok(service.list());}
    public record PreviewRequest(@NotNull AiProvider provider,@NotBlank @Size(max=36) String fileId,@NotNull TaskType taskType,@Size(max=1000) String question){}
    public record ExecuteRequest(@NotBlank @Size(max=100) String previewToken,boolean confirmed){}
}
