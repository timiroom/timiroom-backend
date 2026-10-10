package com.rag.pipeline.common.agent.dto;

import java.util.List;
import java.util.Map;

public record AgentRequest(
    List<Map<String, String>> messages,
    String systemPrompt,
    Object projectContext
) {}
