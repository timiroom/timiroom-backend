package com.rag.pipeline.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.pipeline.phase1.form.FormData;
import com.rag.pipeline.phase2.state.PipelineState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<ProjectResponse> findAll() {
        return projectRepository.findAllByOrderByCreatedAtDesc()
            .stream()
            .map(ProjectResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse findById(String id) {
        return projectRepository.findById(UUID.fromString(id))
            .map(ProjectResponse::from)
            .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + id));
    }

    @Transactional
    public ProjectResponse saveFromPipeline(PipelineState state, FormData formData) {
        String featureListJson = toJson(state.getFeatureList());

        Project project = Project.builder()
            .name(formData.projectName())
            .description(formData.projectDescription() != null ? formData.projectDescription() : "")
            .status("active")
            .prdDocument(state.getPrdDocument())
            .dbSchema(state.getDbSchema())
            .apiSpec(state.getApiSpec())
            .featureList(featureListJson)
            .marketResearch(state.getMarketResearch())
            .build();

        Project saved = projectRepository.save(project);
        log.info("프로젝트 저장 완료 | id: {}, name: {}", saved.getId(), saved.getName());
        return ProjectResponse.from(saved);
    }

    private String toJson(Object obj) {
        try {
            if (obj == null) return "[]";
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
