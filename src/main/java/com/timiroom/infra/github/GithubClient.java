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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.web.util.UriBuilder;

/**
 * GitHub REST API 클라이언트 (WebClient 기반).
 * 인증 헤더는 GithubAppAuthService에서 발급한 토큰을 사용한다.
 */
@Slf4j
@Component
public class GithubClient {

    private static final int MAX_GITHUB_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SOURCE_FILE_BYTES = 192 * 1024;

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
        String commitsPath = repositoryPath(repositoryFullName) + "/commits";
        JsonNode body = get(
                uriBuilder -> uriBuilder.path(commitsPath)
                        .queryParam("sha", branch)
                        .queryParam("per_page", 100)
                        .build(),
                commitsPath + "?sha=" + branch + "&per_page=100",
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

    /** 연결 레포의 전체 이슈 목록. GitHub API가 함께 반환하는 PR 항목은 제외한다. */
    public List<GithubIssueInfo> listIssues(String repositoryFullName, long installationId) {
        // 일정 진척률은 issue 종료/재오픈 상태까지 반영해야 하므로 열린 issue만 제한하지 않는다.
        JsonNode body = get(repositoryPath(repositoryFullName) + "/issues?state=all&per_page=100",
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
        return createIssue(repositoryFullName, installationId, title, body, labels, List.of());
    }

    /** 연결 레포에 이슈를 생성하고 등록된 GitHub 계정을 담당자로 지정한다. */
    public GithubIssueInfo createIssue(String repositoryFullName, long installationId,
                                       String title, String body, List<String> labels, List<String> assignees) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        if (body != null && !body.isBlank()) payload.put("body", body);
        if (labels != null && !labels.isEmpty()) payload.put("labels", labels);
        if (assignees != null && !assignees.isEmpty()) payload.put("assignees", assignees);
        return toIssue(post(repositoryPath(repositoryFullName) + "/issues",
                authService.getInstallationToken(installationId), payload));
    }

    /** 연결 레포의 기존 이슈 제목·본문·라벨·담당자를 갱신한다. null 필드는 기존 값을 유지한다. */
    public GithubIssueInfo updateIssue(String repositoryFullName, long installationId, int issueNumber,
                                       String title, String body, List<String> labels, List<String> assignees) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (title != null) payload.put("title", title);
        if (body != null) payload.put("body", body);
        if (labels != null) payload.put("labels", labels);
        if (assignees != null) payload.put("assignees", assignees);
        if (payload.isEmpty()) throw new IllegalArgumentException("수정할 GitHub Issue 정보가 없습니다");
        return toIssue(patch(repositoryPath(repositoryFullName) + "/issues/" + issueNumber,
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

    /**
     * 두 커밋 사이에 바뀐 파일(최대 100건).
     *
     * PR은 "base 대비 무엇이 달라졌는가"가 GitHub 쪽에 이미 정리되어 있지만,
     * push에는 그런 것이 없어 before와 after 사이를 직접 물어야 한다.
     * 응답 모양은 PR 변경 파일과 같아 정합성 검사가 그대로 받아 쓸 수 있다.
     */
    public List<GithubPullRequestFileInfo> compareCommits(String repositoryFullName, long installationId,
                                                          String base, String head) {
        JsonNode body = get(repositoryPath(repositoryFullName) + "/compare/" + base + "..." + head,
                authService.getInstallationToken(installationId));
        List<GithubPullRequestFileInfo> result = new ArrayList<>();
        for (JsonNode node : body.path("files")) {
            result.add(new GithubPullRequestFileInfo(
                    node.path("filename").asText(),
                    node.path("status").asText(),
                    node.path("additions").asInt(0),
                    node.path("deletions").asInt(0),
                    node.path("patch").asText("")));
        }
        return result;
    }

    /**
     * 특정 ref의 UTF-8 소스 파일 원문을 읽는다. GitHub Contents API의 base64 응답만 허용하고,
     * 너무 큰 파일·디렉터리·바이너리·없는 파일은 정합성 입력에서 안전하게 제외한다.
     */
    public Optional<String> getRepositoryFileContent(String repositoryFullName, long installationId,
                                                     String filename, String ref) {
        if (filename == null || filename.isBlank() || ref == null || ref.isBlank()) return Optional.empty();
        String contentPath = repositoryPath(repositoryFullName) + "/contents/" + filename;
        try {
            JsonNode body = get(
                    uriBuilder -> uriBuilder.path(contentPath).queryParam("ref", ref).build(),
                    contentPath + "?ref=" + ref,
                    authService.getInstallationToken(installationId));
            if (!"file".equals(body.path("type").asText())
                    || !"base64".equalsIgnoreCase(body.path("encoding").asText())
                    || body.path("size").asInt(MAX_SOURCE_FILE_BYTES + 1) > MAX_SOURCE_FILE_BYTES) {
                return Optional.empty();
            }
            byte[] decoded = Base64.getMimeDecoder().decode(body.path("content").asText(""));
            if (decoded.length > MAX_SOURCE_FILE_BYTES || containsNullByte(decoded)) return Optional.empty();
            return Optional.of(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.debug("GitHub 파일 원문 조회 생략 ({}@{}): {}", filename, ref, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean containsNullByte(byte[] value) {
        for (byte item : value) if (item == 0) return true;
        return false;
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
                                                                  String headSha, int score, boolean inconclusive,
        String summary) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("title", consistencyCheckTitle(score, inconclusive));
        output.put("summary", summary);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "timiroom 정합성 검사");
        payload.put("head_sha", headSha);
        payload.put("status", "completed");
        payload.put("conclusion", inconclusive || score < 100 ? "neutral" : "success");
        payload.put("output", output);

        JsonNode response = post(repositoryPath(repositoryFullName) + "/check-runs",
                authService.getInstallationToken(installationId), payload);
        return new GithubCheckRunInfo(response.path("id").asLong(), response.path("html_url").asText(null),
                response.path("conclusion").asText(null));
    }

    static String consistencyCheckTitle(int score, boolean inconclusive) {
        if (inconclusive) return "판정 보류 · 근거 확인 필요";
        return (score < 100 ? "검토 필요 · " : "정합성 확인 완료 · ") + score + "/100";
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

    private JsonNode patch(String path, String bearerToken, Object body) {
        return request(HttpMethod.PATCH, path, bearerToken, body);
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

    private JsonNode get(Function<UriBuilder, URI> uriFunction, String requestDescription, String bearerToken) {
        try {
            return webClient.get()
                    .uri(uriFunction)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            response.bodyToMono(String.class).defaultIfEmpty("")
                                    .map(body -> new IllegalStateException(
                                            "GitHub API 호출 실패 (" + response.statusCode() + " "
                                                    + requestDescription + "): " + body)))
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientRequestException e) {
            throw new IllegalStateException("GitHub API에 연결할 수 없습니다: " + e.getMessage(), e);
        }
    }
}
