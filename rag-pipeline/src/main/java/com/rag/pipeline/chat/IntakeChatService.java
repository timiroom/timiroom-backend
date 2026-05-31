package com.rag.pipeline.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.pipeline.chat.dto.ChatMessageDto;
import com.rag.pipeline.chat.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntakeChatService {

    private final AnthropicChatModel anthropicChatModel;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            당신은 스타트업 프로젝트 기획 인터뷰어 AI입니다.
            사용자가 만들고 싶은 서비스 아이디어를 듣고, 자연스러운 대화를 통해
            프로젝트 계획에 필요한 핵심 정보를 수집합니다.

            ## 수집해야 할 정보
            1. 프로젝트 이름 (앱/서비스 이름)
            2. 프로젝트 설명 (어떤 서비스인지 1-2문장)
            3. 플랫폼: WEB(웹사이트), APP(모바일 앱), WEB_APP(웹+앱 모두)
            4. 핵심 문제:
               - 어떤 불편함을 해결하는가 (currentPainPoint)
               - 지금은 어떻게 해결하고 있는가 (currentSolution)
               - 이 서비스가 잘 되면 어떤 모습인가 (idealState)
            5. 타겟 유저:
               - 어떤 사람들이 쓰는가 (persona)
               - 주로 어디서 사용하는가 (usageEnvironment)
               - 그들의 가장 큰 불편함 (biggestPainPoint)
            6. 기능 목록:
               - 꼭 있어야 하는 기능 (priority: MUST)
               - 있으면 좋은 기능 (priority: SHOULD)

            ## 대화 규칙
            - 한 번에 1-2개의 질문만 하세요
            - 사용자 답변을 반영해서 자연스럽게 이어가세요
            - 친근하고 격려하는 톤으로 대화하세요
            - 이미 언급된 정보는 다시 묻지 마세요
            - 4-7번의 대화 교환으로 정보 수집을 완료하세요

            ## suggestions 규칙
            - 질문에 대해 사용자가 선택할 수 있는 대표적인 예시 답변 3개를 항상 제공하세요
            - 각 제안은 실제로 클릭해서 그대로 전송해도 자연스러운 완성된 문장이어야 합니다
            - 제안은 다양한 방향을 커버해야 합니다 (예: 첫 번째는 가장 일반적인 답, 나머지는 다른 방향)
            - isComplete가 true일 때는 suggestions를 빈 배열로 반환하세요

            ## 절대 규칙 — 응답 형식
            ⚠️ JSON 외에 어떤 텍스트도 출력하지 마세요. 인사말, 설명, 마크다운 코드블록 전부 금지.
            응답은 반드시 { 로 시작해서 } 로 끝나는 순수 JSON 하나만 출력합니다.

            정보 수집 중:
            {
              "isComplete": false,
              "message": "다음 질문 내용 (여기에 인사/공감 포함 가능)",
              "suggestions": ["예시 답변 1", "예시 답변 2", "예시 답변 3"]
            }

            모든 필수 정보(1~6) 수집 완료 시:
            {
              "isComplete": true,
              "message": "좋아요! 충분한 정보가 모였어요. 지금 바로 프로젝트를 시작할게요!",
              "suggestions": [],
              "formData": {
                "projectName": "프로젝트 이름",
                "projectDescription": "프로젝트 설명",
                "platform": "WEB",
                "techStack": [],
                "problemDefinition": {
                  "currentPainPoint": "핵심 불편함",
                  "currentSolution": "현재 해결 방법",
                  "idealState": "이상적인 상태",
                  "businessImpact": null,
                  "motivation": null,
                  "competitorGap": null
                },
                "targetUsers": [
                  {
                    "persona": "타겟 유저 설명",
                    "usageEnvironment": "사용 환경",
                    "biggestPainPoint": "주요 불편함"
                  }
                ],
                "featureDefinition": {
                  "commonFeatures": [],
                  "customFeatures": [
                    {"featureName": "기능명", "description": "기능 설명", "priority": "MUST"}
                  ]
                }
              }
            }
            """;

    public ChatResponse chat(List<ChatMessageDto> messages) {
        List<Message> springMessages = new ArrayList<>();
        springMessages.add(new SystemMessage(SYSTEM_PROMPT));

        for (ChatMessageDto msg : messages) {
            if ("user".equals(msg.role())) {
                springMessages.add(new UserMessage(msg.content()));
            } else if ("assistant".equals(msg.role())) {
                springMessages.add(new AssistantMessage(msg.content()));
            }
        }

        String raw = anthropicChatModel
                .call(new Prompt(springMessages))
                .getResult()
                .getOutput()
                .getContent();

        log.debug("Claude 응답 raw: {}", raw);

        try {
            String json = raw.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }
            if (!json.startsWith("{")) {
                int idx = json.indexOf("{");
                if (idx >= 0) {
                    json = json.substring(idx);
                }
            }

            JsonNode node = objectMapper.readTree(json);
            boolean isComplete = node.path("isComplete").asBoolean(false);
            String message = node.path("message").asText("");
            JsonNode formData = node.has("formData") ? node.get("formData") : null;

            List<String> suggestions = new ArrayList<>();
            if (node.has("suggestions") && node.get("suggestions").isArray()) {
                for (JsonNode s : node.get("suggestions")) {
                    suggestions.add(s.asText());
                }
            }

            return new ChatResponse(message, isComplete, formData, suggestions);
        } catch (Exception e) {
            log.error("Claude 응답 파싱 실패: {}", raw, e);
            return new ChatResponse(raw, false, null, List.of());
        }
    }
}
