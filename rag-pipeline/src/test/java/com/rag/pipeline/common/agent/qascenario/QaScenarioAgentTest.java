package com.rag.pipeline.common.agent.qascenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class QaScenarioAgentTest {

    private final QaScenarioAgent agent =
            new QaScenarioAgent(RestClient.builder(), new ObjectMapper());

    @Test
    void parseGenerateResponse_모델_JSON을_시나리오_목록으로_변환한다() {
        String foundryEnvelope = """
                {
                  "output": [{
                    "type": "message",
                    "content": [{
                      "type": "output_text",
                      "text": "```json\\n{\\"scenarios\\":[{\\"type\\":\\"normal\\",\\"title\\":\\"정상 가입\\",\\"given\\":\\"유효한 이메일\\",\\"when\\":\\"가입 요청\\",\\"then\\":\\"계정이 생성된다\\"}]}\\n```"
                    }]
                  }]
                }
                """;

        var response = agent.parseGenerateResponse(foundryEnvelope, "gpt-5.4-mini");

        assertThat(response.agent()).isEqualTo("QA_SCENARIO_AGENT");
        assertThat(response.scenarios()).containsExactly(
                new QaScenarioGenerateResponse.GeneratedScenario(
                        "NORMAL", "정상 가입", "유효한 이메일", "가입 요청", "계정이 생성된다"));
    }

    @Test
    void parseGenerateResponse_알수없는_타입은_NORMAL로_대체한다() {
        String foundryEnvelope = """
                {
                  "output": [{
                    "type": "message",
                    "content": [{
                      "type": "output_text",
                      "text": "{\\"scenarios\\":[{\\"type\\":\\"weird\\",\\"title\\":\\"t\\",\\"given\\":\\"g\\",\\"when\\":\\"w\\",\\"then\\":\\"결과\\"}]}"
                    }]
                  }]
                }
                """;

        var response = agent.parseGenerateResponse(foundryEnvelope, "gpt-5.4-mini");

        assertThat(response.scenarios()).hasSize(1);
        assertThat(response.scenarios().get(0).type()).isEqualTo("NORMAL");
    }

    @Test
    void parseGenerateResponse_시나리오가_없으면_예외를_던진다() {
        String foundryEnvelope = """
                {
                  "output": [{
                    "type": "message",
                    "content": [{"type": "output_text", "text": "{\\"scenarios\\":[]}"}]
                  }]
                }
                """;

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> agent.parseGenerateResponse(foundryEnvelope, "gpt-5.4-mini"));
    }

    @Test
    void parseEvaluateResponse_모델_JSON을_판정으로_변환한다() {
        String foundryEnvelope = """
                {
                  "output": [{
                    "type": "message",
                    "content": [{
                      "type": "output_text",
                      "text": "{\\"verdict\\":\\"fail\\",\\"reasoning\\":\\"검증 로직이 없습니다\\",\\"evidence\\":[\\"SignupService.java\\"]}"
                    }]
                  }]
                }
                """;

        var response = agent.parseEvaluateResponse(foundryEnvelope, "gpt-5.4-mini");

        assertThat(response.agent()).isEqualTo("QA_SCENARIO_AGENT");
        assertThat(response.verdict()).isEqualTo("FAIL");
        assertThat(response.reasoning()).isEqualTo("검증 로직이 없습니다");
        assertThat(response.evidence()).containsExactly("SignupService.java");
    }

    @Test
    void parseEvaluateResponse_알수없는_verdict는_INCONCLUSIVE로_대체한다() {
        String foundryEnvelope = """
                {
                  "output": [{
                    "type": "message",
                    "content": [{"type": "output_text", "text": "{\\"verdict\\":\\"unknown\\",\\"reasoning\\":\\"근거 부족\\"}"}]
                  }]
                }
                """;

        var response = agent.parseEvaluateResponse(foundryEnvelope, "gpt-5.4-mini");

        assertThat(response.verdict()).isEqualTo("INCONCLUSIVE");
    }
}
