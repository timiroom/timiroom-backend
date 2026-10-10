package com.timiroom.domain.spec.dto;

public record ArtifactWriteCommand(Long artifactId, int expectedVersion, String expectedHash, String content) {}
