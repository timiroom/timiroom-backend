package com.rag.pipeline.common.config;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@AutoConfigureBefore(OpenAiAutoConfiguration.class)
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault());
        return RestClient.builder().requestFactory(factory);
    }
}