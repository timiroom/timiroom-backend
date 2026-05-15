package com.rag.pipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.ai.autoconfigure.chat.client.ChatClientAutoConfiguration;

@SpringBootApplication(exclude = { ChatClientAutoConfiguration.class })
public class RagPipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagPipelineApplication.class, args);
    }

}
