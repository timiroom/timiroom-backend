package com.rag.pipeline.phase2.agent;

import com.rag.pipeline.phase2.state.PipelineState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * PRD 에이전트 — 기능 목록 기반 PRD 문서 생성
 *
 * 역할:
 *   PM 에이전트가 도출한 featureList를 기반으로
 *   구조화된 PRD(제품 요구사항 문서)를 생성합니다.
 *   생성된 PRD는 DBA/API 에이전트의 설계 기준으로 활용됩니다.
 *
 * GPT-4o 사용
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrdAgent {

    private final ChatClient.Builder chatClientBuilder;

    private static final String PRD_PROMPT = """
        당신은 시니어 소프트웨어 아키텍트 겸 PM입니다.
        아래 요구사항과 기능 목록을 바탕으로 실무 수준의 PRD를 JSON 형식으로 작성하세요.
        
        응답 형식:
        {
          "projectOverview": "프로젝트 한 줄 개요",
          "background": "배경 및 문제 정의",
          "goals": ["목표1", "목표2"],
          "kpi": [
            {"metric": "월별 신규 사용자", "target": "100명"},
            {"metric": "사용자 만족도", "target": "80%% 이상"}
          ],
          "coreFeatures": [
            {
              "name": "기능명",
              "description": "기능 설명",
              "requirements": ["요구사항1", "요구사항2"]
            }
          ],
          "userFlow": ["1. 회원가입 및 로그인", "2. 메인 화면 진입", "3. 기능 사용"],
          "uxConsiderations": [
            {"category": "직관적인 UI", "detail": "초보자도 쉽게 사용할 수 있는 버튼 배치"},
            {"category": "반응 속도", "detail": "제어 시 즉각적인 피드백 제공"}
          ],
          "nonFunctionalRequirements": {
            "performance": "응답속도 1초 이하, 동시접속 1만명 처리",
            "security": "OAuth 2.0 인증, HTTPS/TLS 암호화, 데이터 암호화 저장",
            "legal": "개인정보보호법 및 GDPR 준수, 탈퇴 시 데이터 완전 삭제"
          },
          "releaseSchedule": [
            {"date": "1주차", "milestone": "기획 확정", "description": "요구사항 정리 및 구조 설계"},
            {"date": "2~3주차", "milestone": "개발 시작", "description": "핵심 기능 단계별 구현"},
            {"date": "4주차", "milestone": "내부 테스트", "description": "기능 통합 후 QA 테스트"},
            {"date": "5주차", "milestone": "정식 런칭", "description": "전 사용자 대상 오픈"}
          ],
          "risks": [
            {
              "title": "리스크 제목",
              "priority": "상",
              "description": "리스크 상세 설명",
              "strategy": "대응 전략"
            }
          ],
          "fsd": [
            {
              "id": "FSD_001",
              "category": "기능",
              "description": "요구사항 설명",
              "action": "구현 액션",
              "note": "비고"
            }
          ],
          "operationPolicy": [
            {
              "id": "POL_001",
              "category": "탈퇴 정책",
              "description": "정책 설명",
              "action": "처리 액션",
              "note": "비고"
            }
          ]
        }
        
        규칙:
        - JSON 외 다른 텍스트는 절대 포함하지 마세요
        - coreFeatures는 기능 목록의 모든 항목을 반드시 포함하세요
        - kpi는 측정 가능한 수치로 작성하세요
        - fsd는 각 핵심 기능마다 최소 1개 이상 작성하세요
        - operationPolicy는 서비스 운영에 필요한 정책을 작성하세요 (탈퇴, 오류 처리, 보안 점검 등)
        - risks는 우선순위 상/중/하로 분류하고 대응 전략을 반드시 포함하세요
        - 퍼센트 기호는 반드시 %%%%로 이스케이프하세요
        
        사용자 요구사항:
        %s
        
        기능 목록:
        %s
        """;

    /**
     * PRD 에이전트 실행
     * featureList를 기반으로 PRD 문서 생성 후 PipelineState에 저장
     */
    public PipelineState execute(PipelineState state) {
        log.info("PRD 에이전트 시작 — {} 기능 기반 PRD 생성", state.getFeatureList().size());

        ChatClient chatClient = chatClientBuilder
                .defaultOptions(
                        org.springframework.ai.openai.OpenAiChatOptions.builder()
                                .withModel("gpt-4o")
                                .withTemperature(0.1)
                                .build()
                )
                .build();

        String featureListStr = String.join("\n- ", state.getFeatureList());
        featureListStr = "- " + featureListStr;

        String response = chatClient
                .prompt(String.format(PRD_PROMPT,
                        state.getUserQuery(),
                        featureListStr))
                .call()
                .content();

        String prdDocument = response.trim()
                .replaceAll("```json", "")
                .replaceAll("```", "")
                .trim();

        log.info("PRD 에이전트 완료 — PRD 문서 생성 ({} chars)", prdDocument.length());

        return state.toBuilder()
                .prdDocument(prdDocument)
                .statusMessage("PRD 에이전트 완료 — PRD 문서 생성")
                .build();
    }
}