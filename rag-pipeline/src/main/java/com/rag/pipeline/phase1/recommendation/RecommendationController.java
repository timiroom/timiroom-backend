package com.rag.pipeline.phase1.recommendation;

import com.rag.pipeline.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/recommendation")
@RequiredArgsConstructor
public class RecommendationController {

    private final TechStackRecommendationService techStackService;
    private final PersonaRecommendationService personaService;
    private final FeatureRecommendationService featureService;

    @PostMapping("/tech-stack")
    public ApiResponse<TechStackResponse> techStack(@RequestBody TechStackRequest req) {
        return ApiResponse.ok(techStackService.recommend(
            req.projectName(), req.projectDescription(), req.platform()));
    }

    @PostMapping("/persona")
    public ApiResponse<PersonaRecommendationResponse> persona(
        @RequestBody PersonaRecommendationRequest req) {
        return ApiResponse.ok(personaService.recommend(req));
    }

    @PostMapping("/features")
    public ApiResponse<FeatureRecommendationResponse> features(
        @RequestBody FeatureRecommendationRequest req) {
        return ApiResponse.ok(featureService.recommend(req));
    }
}
