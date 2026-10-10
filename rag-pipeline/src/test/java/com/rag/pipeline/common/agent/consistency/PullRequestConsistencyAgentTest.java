package com.rag.pipeline.common.agent.consistency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PullRequestConsistencyAgentTest {

    private final PullRequestConsistencyAgent agent =
            new PullRequestConsistencyAgent(RestClient.builder(), new ObjectMapper());

    @Test
    void parseResponse_모델_JSON을_구조화된_판정으로_변환한다() throws Exception {
        String foundryEnvelope = """
                {
                  "output": [{
                    "type": "message",
                    "content": [{
                      "type": "output_text",
                      "text": "```json\\n{\\"passed\\":false,\\"summary\\":\\"API 명세 확인 필요\\",\\"findings\\":[{\\"severity\\":\\"WARNING\\",\\"area\\":\\"API\\",\\"message\\":\\"POST /api/v1/tasks가 API_SPEC에 없습니다.\\"}]}\\n```"
                    }]
                  }]
                }
                """;

        var response = agent.parseResponse(foundryEnvelope, "gpt-5.4-mini");

        assertThat(response.agent()).isEqualTo("PR_CONSISTENCY_AGENT");
        assertThat(response.passed()).isFalse();
        assertThat(response.findings()).containsExactly(
                new PullRequestConsistencyAgentResponse.Finding(
                        "WARNING", "API", "POST /api/v1/tasks가 API_SPEC에 없습니다."));
    }

    @Test
    void buildContext_PR_명세와_diff를_모두_포함한다() {
        var request = new PullRequestConsistencyAgentRequest(
                "gpt-5.4-mini",
                "timiroom/timiroom-backend",
                42,
                "feat: task API",
                "작업 API를 추가합니다.",
                "feature/task",
                "develop",
                "GET /api/v1/tasks",
                "tasks(id, title)",
                List.of(new PullRequestConsistencyAgentRequest.ChangedFile(
                        "TaskController.java", "modified", 3, 0,
                        "+ @GetMapping(\"/api/v1/tasks\")")));

        String context = agent.buildContext(request);

        assertThat(context)
                .contains("timiroom/timiroom-backend")
                .contains("GET /api/v1/tasks")
                .contains("tasks(id, title)")
                .contains("TaskController.java");
    }
}
