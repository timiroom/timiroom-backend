package com.timiroom.domain.github.dto;

/** 규칙 기반 명세 대조 결과 한 건. severity는 PASS, INFO, WARNING 중 하나다. */
public record ConsistencyFinding(
        String severity,
        String area,
        String message
) {}
