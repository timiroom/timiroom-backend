package com.rag.pipeline.phase2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class GenerateRequest {

    @NotBlank(message = "요구사항을 입력해주세요")
    @Size(min = 10, message = "요구사항은 최소 10자 이상 입력해주세요")
    @Size(max = 2000, message = "요구사항은 최대 2000자까지 입력 가능합니다")
    private String query;
}