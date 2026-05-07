package com.rag.pipeline.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(
                "http://localhost:3000",
                "http://localhost:5500",   // VSCode Live Server
                "http://127.0.0.1:5500"
            )
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders("Content-Type", "Cache-Control", "Transfer-Encoding", "X-Accel-Buffering")
            .allowCredentials(true)
            .maxAge(3600);
    }
}
