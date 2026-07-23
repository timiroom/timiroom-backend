package com.timiroom.infra.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.timiroom.infra.github.dto.GithubInstallationInfo;
import com.timiroom.infra.github.dto.GithubBranchInfo;
import com.timiroom.infra.github.dto.GithubCommitInfo;
import com.timiroom.infra.github.dto.GithubCheckRunInfo;
import com.timiroom.infra.github.dto.GithubIssueInfo;
import com.timiroom.infra.github.dto.GithubPullRequestFileInfo;
import com.timiroom.infra.github.dto.GithubPullRequestInfo;
import com.timiroom.infra.github.dto.GithubPullRequestReviewInfo;
import com.timiroom.infra.github.dto.GithubRepoInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub REST API 클라이언트 (WebClient 기반).
 * 인증 헤더는 GithubAppAuthService에서 발급한 토큰을 사용한다.
 */
@Slf4j
@Component
public class GithubClient {

    private static final int MAX_GITHUB_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final GithubAppAuthService authService;
    private final WebClient webClient;

    public GithubClient(
            GithubAppAuthService authService,
            @Value("${github.api-base-url:https://api.github.com}") String baseUrl) {
        this.authService = authService;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(configurer ->
                        configurer.defaultCodecs().maxInMemorySize(MAX_GITHUB_RESPONSE_BYTES))
                .build();
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

    /** 연결된 레포의 브랜치 목록. installation token으로만 조회한다. */
    public List<GithubBranchInfo> listBranches(String repositoryFullName, long installationId) {
        JsonNode body = get(repositoryPath(repositoryFullName) + "/branches?per_page=100",
                authService.getInstallationToken(installationId));
        List<GithubBranchInfo> result = new ArrayList<>();
        for (JsonNode node : body) {
            result.add(new GithubBranchInfo(
                    node.path("name").asText(),
                    node.path("commit").path("sha").asText(),
                    node.path("protected").asBoolean(false)));
        }
        return result;
    }

    /** 연결된 레포의 특정 브랜치 커밋 히스토리(최대 100건). */
    public List<GithubCommitInfo> listCommits(String repositoryFullName, long installationId, String branch) {
        String encodedBranch = java.net.URLEncoder.encode(branch, java.nio.charset.StandardCharsets.UTF_8);
        JsonNode body = get(repositoryPath(repositoryFullName) + "/commits?sha=" + encodedBranch + "&per_page=100",
                authService.getInstallationToken(installationId));
        List<GithubCommitInfo> result = new ArrayList<>();
        for (JsonNode node : body) {
            JsonNode commit = node.path("commit");
            result.add(new GithubCommitInfo(
                    node.path("sha").asText(),
                    commit.path("message").asText(),
                    commit.path("author").path("name").asText(),
                    node.path("author").path("login").asText(null),
                    commit.path("author").path("date").asText(null),
                    node.path("html_url").asText(null)));
        }
        return result;
    }

    /** 연결 레포의 열린 이슈 목록. GitHub API가 함께 반환하는 PR 항목은 제외한다. */
    public List<GithubIssueInfo> listIssues(String repositoryFullName, long installationId) {
        JsonNode body = get(repositoryPath(repositoryFullName) + "/issues?state=open&per_page=100",
                authService.getInstallationToken(installationId));
        List<GithubIssueInfo> result = new ArrayList<>();
        for (JsonNode node : body) {
            if (node.hasNonNull("pull_request")) continue;
            result.add(toIssue(node));
        }
        return result;
    }

    /** 연결 레포에 이슈를 생성한다. */
    public GithubIssueInfo createIssue(String repositoryFullName, long installationId,
                                       String title, String body, List<String> labels) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        if (body != null && !body.isBlank()) payload.put("body", body);
        if (labels != null && !labels.isEmpty()) payload.put("labels", labels);
        return toIssue(post(repositoryPath(repositoryFullName) + "/issues",
                authService.getInstallationToken(installationId), payload));
    }

    /** 연결 레포의 열린 PR 목록. */
    public List<GithubPullRequestInfo> listPullRequests(String repositoryFullName, long installationId) {
        JsonNode body = get(repositoryPath(repositoryFullName) + "/pulls?state=open&per_page=100",
                authService.getInstallationToken(installationId));
        List<GithubPullRequestInfo> result = new ArrayList<>();
        for (JsonNode node : body) result.add(toPullRequest(node));
        return result;
    }

    /** 특정 PR의 현재 head SHA를 포함한 상세 정보. */
    public GithubPullRequestInfo getPullRequest(String repositoryFullName, long installationId, int pullNumber) {
        return toPullRequest(get(repositoryPath(repositoryFullName) + "/pulls/" + pullNumber,
                authService.getInstallationToken(installationId)));
    }

    /** PR 변경 파일(최대 100건)의 patch 요약. */
    public List<GithubPullRequestFileInfo> listPullRequestFiles(String repositoryFullName, long installationId, int pullNumber) {
        JsonNode body = get(repositoryPath(repositoryFullName) + "/pulls/" + pullNumber + "/files?per_page=100",
                authService.getInstallationToken(installationId));
        List<GithubPullRequestFileInfo> result = new ArrayList<>();
        for (JsonNode node : body) {
            result.add(new GithubPullRequestFileInfo(
                    node.path("filename").asText(),
                    node.path("status").asText(),
                    node.path("additions").asInt(0),
                    node.path("deletions").asInt(0),
                    node.path("patch").asText("")));
        }
        return result;
    }

    /** PR에 summary review comment를 게시한다. 자동 승인/변경 요청은 사용하지 않는다. */
    public GithubPullRequestReviewInfo createPullRequestCommentReview(String repositoryFullName, long installationId,
                                                                        int pullNumber, String headSha, String body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (headSha != null && !headSha.isBlank()) payload.put("commit_id", headSha);
        payload.put("body", body);
        payload.put("event", "COMMENT");
        JsonNode response = post(repositoryPath(repositoryFullName) + "/pulls/" + pullNumber + "/reviews",
                authService.getInstallationToken(installationId), payload);
        return new GithubPullRequestReviewInfo(
                response.path("id").asLong(),
                response.path("html_url").asText(null),
                response.path("state").asText(null));
    }

    /** PR head commit에 완료된 정합성 check run을 게시한다. */
    public GithubCheckRunInfo createCompletedConsistencyCheckRun(String repositoryFullName, long installationId,
                                                                  String headSha, int score, boolean hasWarnings,
                                                                  String summary) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("title", "정합성 " + score + "/100");
        output.put("summary", summary);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "timiroom 정합성 검사");
        payload.put("head_sha", headSha);
        payload.put("status", "completed");
        payload.put("conclusion", hasWarnings ? "neutral" : "success");
        payload.put("output", output);

        JsonNode response = post(repositoryPath(repositoryFullName) + "/check-runs",
                authService.getInstallationToken(installationId), payload);
        return new GithubCheckRunInfo(response.path("id").asLong(), response.path("html_url").asText(null),
                response.path("conclusion").asText(null));
    }

    private GithubIssueInfo toIssue(JsonNode node) {
        List<String> labels = new ArrayList<>();
        for (JsonNode label : node.path("labels")) labels.add(label.path("name").asText());
        return new GithubIssueInfo(
                node.path("number").asInt(),
                node.path("title").asText(),
                node.path("body").asText(null),
                node.path("state").asText(),
                node.path("html_url").asText(null),
                node.path("user").path("login").asText(null),
                node.path("created_at").asText(null),
                labels);
    }

    private GithubPullRequestInfo toPullRequest(JsonNode node) {
        return new GithubPullRequestInfo(
                node.path("number").asInt(),
                node.path("title").asText(),
                node.path("body").asText(null),
                node.path("state").asText(),
                node.path("draft").asBoolean(false),
                node.path("head").path("sha").asText(null),
                node.path("head").path("ref").asText(null),
                node.path("base").path("ref").asText(null),
                node.path("html_url").asText(null),
                node.path("user").path("login").asText(null),
                node.path("updated_at").asText(null));
    }

    private String repositoryPath(String repositoryFullName) {
        if (repositoryFullName == null || !repositoryFullName.matches("^[^/]+/[^/]+$")) {
            throw new IllegalArgumentException("올바르지 않은 GitHub 레포 이름입니다: " + repositoryFullName);
        }
        return "/repos/" + repositoryFullName;
    }

    private JsonNode get(String pathWithQuery, String bearerToken) {
        return request(HttpMethod.GET, pathWithQuery, bearerToken, null);
    }

    private JsonNode post(String path, String bearerToken, Object body) {
        return request(HttpMethod.POST, path, bearerToken, body);
    }

    private JsonNode request(HttpMethod method, String pathWithQuery, String bearerToken, Object body) {
        try {
            WebClient.RequestBodySpec request = webClient.method(method)
                    .uri(pathWithQuery)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28");
            WebClient.RequestHeadersSpec<?> headers = body == null ? request : request.bodyValue(body);
            return headers
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
