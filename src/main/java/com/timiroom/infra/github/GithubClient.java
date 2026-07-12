package com.timiroom.infra.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.timiroom.infra.github.dto.GithubInstallationInfo;
import com.timiroom.infra.github.dto.GithubRepoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.util.ArrayList;
import java.util.List;

/**
 * GitHub REST API 클라이언트 (WebClient 기반).
 * 인증 헤더는 GithubAppAuthService에서 발급한 토큰을 사용한다.
 */
@Slf4j
@Component
public class GithubClient {

    private final GithubAppAuthService authService;
    private final WebClient webClient;

    public GithubClient(
            GithubAppAuthService authService,
            @Value("${github.api-base-url:https://api.github.com}") String baseUrl) {
        this.authService = authService;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /** 이 App의 설치 목록 — App JWT 인증. GET /app/installations */
    public List<GithubInstallationInfo> listAppInstallations() {
        JsonNode body = get("/app/installations?per_page=100", authService.createAppJwt());
        List<GithubInstallationInfo> result = new ArrayList<>();
        for (JsonNode node : body) {
            result.add(new GithubInstallationInfo(
                    node.get("id").asLong(),
                    node.path("account").path("login").asText(),
                    node.path("account").path("type").asText()));
        }
        return result;
    }

    /**
     * 설치가 접근 가능한 레포 목록 — installation token 인증.
     * GET /installation/repositories (per_page=100, 팀 규모상 1페이지로 충분)
     */
    public List<GithubRepoInfo> listInstallationRepositories(long installationId) {
        JsonNode body = get("/installation/repositories?per_page=100",
                authService.getInstallationToken(installationId));
        List<GithubRepoInfo> result = new ArrayList<>();
        for (JsonNode node : body.path("repositories")) {
            result.add(new GithubRepoInfo(
                    node.get("id").asLong(),
                    node.get("full_name").asText(),
                    node.path("default_branch").asText(null),
                    node.path("private").asBoolean(false)));
        }
        return result;
    }

    private JsonNode get(String pathWithQuery, String bearerToken) {
        try {
            return webClient.get()
                    .uri(pathWithQuery)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).defaultIfEmpty("")
                            .map(b -> new IllegalStateException(
                                    "GitHub API 호출 실패 (" + r.statusCode() + " " + pathWithQuery + "): " + b)))
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientRequestException e) {
            throw new IllegalStateException("GitHub API에 연결할 수 없습니다: " + e.getMessage(), e);
        }
    }
}
