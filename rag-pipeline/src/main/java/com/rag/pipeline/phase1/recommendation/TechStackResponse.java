package com.rag.pipeline.phase1.recommendation;

import java.util.List;

public record TechStackResponse(
    List<String> frontend,
    List<String> backend,
    List<String> database,
    List<String> devops,
    List<String> mobile
) {
    public static TechStackResponse defaultFor(String platform) {
        return switch (platform.toUpperCase()) {
            case "APP" -> new TechStackResponse(
                List.of(), List.of("Spring Boot", "Java 21"),
                List.of("PostgreSQL"), List.of("Docker"), List.of("React Native")
            );
            case "WEB_APP" -> new TechStackResponse(
                List.of("React", "TypeScript"), List.of("Spring Boot", "Java 21"),
                List.of("PostgreSQL"), List.of("Docker"), List.of("React Native")
            );
            default -> new TechStackResponse(
                List.of("React", "TypeScript"), List.of("Spring Boot", "Java 21"),
                List.of("PostgreSQL"), List.of("Docker"), List.of()
            );
        };
    }
}
