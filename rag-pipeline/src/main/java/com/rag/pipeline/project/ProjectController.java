package com.rag.pipeline.project;

import com.rag.pipeline.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    public ApiResponse<List<ProjectResponse>> list() {
        return ApiResponse.ok(projectService.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectResponse> get(@PathVariable String id) {
        return ApiResponse.ok(projectService.findById(id));
    }
}
