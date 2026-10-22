package com.timiroom.domain.usage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(UsageProperties.class)
public class UsageConfig {
}
