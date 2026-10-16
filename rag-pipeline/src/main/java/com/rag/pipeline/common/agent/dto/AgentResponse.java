package com.rag.pipeline.common.agent.dto;

import java.util.Map;

public record AgentResponse(String content, Map<String, Object> usage) {}
