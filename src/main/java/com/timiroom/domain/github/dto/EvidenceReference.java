package com.timiroom.domain.github.dto;

/** 명세 또는 구현 원문에서 finding을 뒷받침하는 정확한 위치. */
public record EvidenceReference(
        String sourceType,
        String source,
        Integer line,
        String quote
) {}
