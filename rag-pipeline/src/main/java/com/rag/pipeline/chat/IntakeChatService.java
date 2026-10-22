package com.rag.pipeline.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.pipeline.chat.dto.ChatMessageDto;
import com.rag.pipeline.chat.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntakeChatService {

    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    @Value("${spring.ai.openai.api-key}")
    private String foundryApiKey;

    private static final String MODEL = "gpt-5.4-mini";
    @Value("${app.ai.foundry.responses-url}")
    private String foundryUrl;

    private static final String SYSTEM_PROMPT = """
            당신은 스타트업 프로젝트 기획 인터뷰어 AI입니다.
            아래 6단계 순서를 반드시 지켜서 사용자의 프로젝트 정보를 수집합니다.
            서비스 이름은 사용자가 아닌 AI가 대화 내용을 바탕으로 직접 작성합니다. 절대 사용자에게 서비스 이름을 물어보지 마세요.

            ## 6단계 질문 (순서 및 질문 문장 변경 금지)

            [Step 1 — 서비스 아이디어]
            질문 문장: "어떤 서비스를 만들고 싶으신가요? 어떤 문제를 해결하는 서비스인지 자유롭게 설명해 주세요."
            수집 항목: projectDescription

            [Step 2 — 플랫폼]
            질문 문장: "어떤 플랫폼에서 운영할 계획인가요? 웹(WEB), 모바일 앱(APP), 웹+앱 모두(WEB_APP) 중 선택해 주세요."
            수집 항목: platform

            [Step 3 — 해결 문제]
            질문 문장: "현재 어떤 불편함을 해결하려고 하시나요? 지금은 어떻게 해결하고 계신지도 알려주세요."
            수집 항목: currentPainPoint, currentSolution

            [Step 4 — 이상적인 상태]
            질문 문장: "이 서비스가 잘 됐을 때 어떤 모습을 기대하시나요?"
            수집 항목: idealState

            [Step 5 — 타겟 유저]
            질문 문장: "이 서비스를 주로 사용할 사람은 누구인가요? 주로 어떤 환경에서 사용할지도 알려주세요."
            수집 항목: persona, usageEnvironment, biggestPainPoint

            [Step 6 — 기능 목록]
            질문 문장: "꼭 있어야 하는 핵심 기능들을 알려주세요. 있으면 좋겠다고 생각하시는 기능도 함께 말씀해 주세요."
            수집 항목: customFeatures (priority: MUST / SHOULD)

            ## 대화 진행 규칙
            - Step 1부터 Step 6까지 반드시 순서대로 진행하세요. 건너뛰거나 순서 변경 금지.
            - Step 1: "안녕하세요! 프로젝트 기획을 시작해 볼게요. " 뒤에 Step 1 질문 문장을 그대로 붙이세요.
            - Step 2~6: 이전 답변을 한 문장으로 공감/요약한 후 → 해당 단계 질문 문장을 그대로 사용하세요.
            - 질문 문장은 위에 정의된 그대로 사용하세요. 표현을 바꾸거나 다른 질문으로 대체하지 마세요.
            - 존댓말을 사용하세요.
            - Step 6 답변 수신 후 isComplete: true를 설정하세요.

            ## suggestions 규칙
            - 현재 단계에 맞는 예시 답변 3개를 제공하세요.
            - 각 제안은 그대로 전송해도 자연스러운 완성된 문장이어야 합니다.
            - 서로 다른 방향의 예시를 제공하세요.
            - isComplete가 true일 때는 빈 배열을 반환하세요.

            ## 절대 규칙 — 응답 형식
            JSON 형식으로만 출력하세요. { 로 시작해서 } 로 끝나는 순수 JSON만 출력합니다.
            마크다운 코드블록(```) 금지. 설명 텍스트 금지. JSON 외 어떤 텍스트도 금지.

            수집 중 (Step 1~5):
            {
              "isComplete": false,
              "step": <현재 단계 숫자 1~6>,
              "message": "<공감/요약 1문장 + 해당 단계 질문 문장>",
              "suggestions": ["예시 1", "예시 2", "예시 3"]
            }

            Step 6 완료 시:
            {
              "isComplete": true,
              "step": 6,
              "message": "좋아요! 충분한 정보가 모였습니다. 지금 바로 프로젝트를 시작할게요!",
              "suggestions": [],
              "formData": {
                "projectName": "<대화 내용을 바탕으로 AI가 직접 작성한 한국어 서비스명 (간결하고 기억하기 쉬운 이름, 2~5글자 권장)>",
                "projectDescription": "<수집된 서비스 아이디어 요약>",
                "platform": "WEB | APP | WEB_APP",
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
                    {"featureName": "기능명", "description": "기능 설명", "priority": "MUST"},
                    {"featureName": "기능명", "description": "기능 설명", "priority": "SHOULD"}
                  ]
                }
              }
            }
            """;

    public ChatResponse chat(List<ChatMessageDto> messages) {
        log.info("채팅 처리 시작 | 메시지 수: {}", messages.size());

        // user/assistant 메시지 변환
        // 마지막 user 메시지에 "json" 단어를 포함시켜 json_object 포맷 요구사항 충족
        List<ChatMessageDto> relevant = messages.stream()
                .filter(m -> "user".equals(m.role()) || "assistant".equals(m.role()))
                .toList();

        List<Map<String, String>> input = new ArrayList<>();
        for (int i = 0; i < relevant.size(); i++) {
            ChatMessageDto msg = relevant.get(i);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("role", msg.role());
            boolean isLastUser = "user".equals(msg.role()) && i == relevant.size() - 1;
            m.put("content", isLastUser
                    ? msg.content() + "\n(응답은 반드시 json 형식으로)"
                    : msg.content());
            input.add(m);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", MODEL);
        payload.put("instructions", SYSTEM_PROMPT);
        payload.put("input", input);
        payload.put("max_output_tokens", 1000);
        payload.put("temperature", 0.3);
        payload.put("text", Map.of("format", Map.of("type", "json_object")));

        try {
            byte[] rawBytes = restClientBuilder.build()
                    .post()
                    .uri(foundryUrl)
                    .header("Authorization", "Bearer " + foundryApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(byte[].class);

            String raw = new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);
            String text = extractText(objectMapper.readTree(raw));
            log.debug("모델 응답 원문: {}", text.length() > 200 ? text.substring(0, 200) + "..." : text);

            return parseResponse(text);

        } catch (Exception e) {
            log.error("채팅 처리 실패: {}", e.getMessage(), e);
            return new ChatResponse(
                    "일시적인 오류가 발생했습니다. 다시 시도해 주세요.",
                    false, 0, null, List.of());
        }
    }

    private String extractText(JsonNode root) {
        // Azure AI Foundry Responses API 응답 형식
        for (JsonNode item : root.path("output")) {
            if ("message".equals(item.path("type").asText())) {
                for (JsonNode block : item.path("content")) {
                    if ("output_text".equals(block.path("type").asText())) {
                        return block.path("text").asText();
                    }
                }
            }
        }
        // 표준 OpenAI Chat Completions 응답 형식 (fallback)
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            return choices.get(0).path("message").path("content").asText("");
        }
        log.warn("응답에서 텍스트 추출 실패. 응답 구조: {}", root.toPrettyString());
        return "";
    }

    private ChatResponse parseResponse(String raw) {
        try {
            String json = raw.trim();

            // 1. 마크다운 코드블록 제거 (반드시 JSON 검증보다 먼저)
            if (json.startsWith("```")) {
                json = json.replaceAll("(?s)^```[a-zA-Z]*\\n?", "")
                           .replaceAll("(?s)```\\s*$", "")
                           .trim();
            }

            // 2. JSON 범위 탐색 ({ ... } 추출)
            int start = json.indexOf("{");
            int end = json.lastIndexOf("}");
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }

            // 3. JSON 파싱
            JsonNode node = objectMapper.readTree(json);

            boolean isComplete = node.path("isComplete").asBoolean(false);
            int step = node.path("step").asInt(0);
            String message = node.path("message").asText("");
            JsonNode formData = node.has("formData") ? node.get("formData") : null;

            List<String> suggestions = new ArrayList<>();
            for (JsonNode s : node.path("suggestions")) {
                suggestions.add(s.asText());
            }

            log.info("채팅 응답 | step: {}, isComplete: {}, suggestions: {}개", step, isComplete, suggestions.size());
            return new ChatResponse(message, isComplete, step, formData, suggestions);

        } catch (Exception e) {
            // JSON 파싱 실패 시 원문 텍스트를 message로 폴백
            log.warn("JSON 파싱 실패 — 텍스트 폴백: {}", raw.length() > 100 ? raw.substring(0, 100) + "..." : raw);
            return new ChatResponse(raw, false, 0, null,
                    List.of("네, 계속해주세요", "좀 더 설명해주세요", "다음 질문으로 넘어갈게요"));
        }
    }
}
