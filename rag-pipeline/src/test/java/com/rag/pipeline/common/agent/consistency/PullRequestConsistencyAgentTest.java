package com.rag.pipeline.common.agent.consistency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

        var response = agent.parseResponse(foundryEnvelope, "FOUNDRY", "gpt-5.4-mini");

        assertThat(response.agent()).isEqualTo("PR_CONSISTENCY_AGENT");
        assertThat(response.provider()).isEqualTo("FOUNDRY");
        assertThat(response.passed()).isFalse();
        assertThat(response.findings()).containsExactly(
                new PullRequestConsistencyAgentResponse.Finding(
                        "WARNING", "API", "POST /api/v1/tasks가 API_SPEC에 없습니다."));
    }

    @Test
    void buildContext_PR_명세와_diff를_모두_포함한다() {
        var request = new PullRequestConsistencyAgentRequest(
                "EXAONE",
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

    @Test
    void review_EXAONE을_선택하면_OpenAI_호환_Chat_Completions를_호출한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PullRequestConsistencyAgent exaoneAgent = new PullRequestConsistencyAgent(builder, new ObjectMapper());
        ReflectionTestUtils.setField(exaoneAgent, "exaoneUrl", "http://localhost:8000/v1/chat/completions");
        ReflectionTestUtils.setField(exaoneAgent, "exaoneApiKey", "");
        ReflectionTestUtils.setField(exaoneAgent, "defaultProvider", "EXAONE");
        ReflectionTestUtils.setField(exaoneAgent, "defaultExaoneModel", "LGAI-EXAONE/K-EXAONE-236B-A23B");

        server.expect(requestTo("http://localhost:8000/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "model":"LGAI-EXAONE/K-EXAONE-236B-A23B",
                          "temperature":1.0,
                          "chat_template_kwargs":{"enable_thinking":false}
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\\"passed\\\":true,\\\"summary\\\":\\\"정합\\\",\\\"findings\\\":[{\\\"severity\\\":\\\"PASS\\\",\\\"area\\\":\\\"API\\\",\\\"message\\\":\\\"명세와 일치합니다.\\\"}]}"}}]}
                        """, MediaType.APPLICATION_JSON));

        PullRequestConsistencyAgentResponse response = exaoneAgent.review(sampleRequest(null, null));

        assertThat(response.provider()).isEqualTo("EXAONE");
        assertThat(response.model()).isEqualTo("LGAI-EXAONE/K-EXAONE-236B-A23B");
        assertThat(response.passed()).isTrue();
        server.verify();
    }

    @Test
    void review_FOUNDRY를_선택하면_기존_Responses_API를_호출한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PullRequestConsistencyAgent foundryAgent = new PullRequestConsistencyAgent(builder, new ObjectMapper());
        ReflectionTestUtils.setField(foundryAgent, "foundryUrl", "https://foundry.example/openai/v1/responses");
        ReflectionTestUtils.setField(foundryAgent, "foundryApiKey", "foundry-key");
        ReflectionTestUtils.setField(foundryAgent, "defaultProvider", "FOUNDRY");
        ReflectionTestUtils.setField(foundryAgent, "defaultFoundryModel", "gpt-5.4-mini");

        server.expect(requestTo("https://foundry.example/openai/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer foundry-key"))
                .andExpect(content().json("""
                        {"model":"gpt-5.4-mini","max_output_tokens":4000}
                        """, false))
                .andRespond(withSuccess("""
                        {"output":[{"type":"message","content":[{"type":"output_text","text":"{\\\"passed\\\":true,\\\"summary\\\":\\\"정합\\\",\\\"findings\\\":[{\\\"severity\\\":\\\"PASS\\\",\\\"area\\\":\\\"DB\\\",\\\"message\\\":\\\"명세와 일치합니다.\\\"}]}"}]}]}
                        """, MediaType.APPLICATION_JSON));

        PullRequestConsistencyAgentResponse response = foundryAgent.review(sampleRequest("FOUNDRY", null));

        assertThat(response.provider()).isEqualTo("FOUNDRY");
        assertThat(response.model()).isEqualTo("gpt-5.4-mini");
        server.verify();
    }

    private PullRequestConsistencyAgentRequest sampleRequest(String provider, String model) {
        return new PullRequestConsistencyAgentRequest(
                provider, model, "timiroom/timiroom-backend", 42, "feat: task API", "",
                "feature/task", "develop", "GET /api/v1/tasks", "tasks(id, title)", List.of());
    }
}
