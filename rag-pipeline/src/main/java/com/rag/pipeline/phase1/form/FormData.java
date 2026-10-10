package com.rag.pipeline.phase1.form;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 5단계 프로젝트 생성 폼 전체 DTO
 *
 * [임시 구조] timiroom-backend 미완성으로 rag-pipeline이 직접 수신
 * [최종 구조] timiroom-backend → DB/S3 저장 → PipelineRequest{projectId, formData, pdfUrls[]} 전달
 *
 * PDF 파일은 multipart/form-data의 files[] 파트로 별도 수신
 */
public record FormData(

    // Step 1: 프로젝트 개요
    @NotBlank(message = "프로젝트 이름은 필수입니다")
    String projectName,

    @NotBlank(message = "프로젝트 설명은 필수입니다")
    String projectDescription,

    @NotNull(message = "플랫폼 선택은 필수입니다")
    PlatformType platform,

    List<String> techStack,  // 선택사항

    // Step 2: 문제정의
    @NotNull @Valid
    ProblemDefinition problemDefinition,

    // Step 3: 레퍼런스 PDF → multipart files[] 로 별도 수신

    // Step 4: 타겟유저
    @NotEmpty(message = "타겟유저는 최소 1명 필요합니다")
    @Valid
    List<TargetUser> targetUsers,

    // Step 5: 기능정의
    @NotNull @Valid
    FeatureDefinition featureDefinition

) {}
