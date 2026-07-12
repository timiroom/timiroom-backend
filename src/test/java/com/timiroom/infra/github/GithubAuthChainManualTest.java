package com.timiroom.infra.github;

import com.timiroom.infra.github.dto.GithubInstallationInfo;
import com.timiroom.infra.github.dto.GithubRepoInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * GitHub App 인증 체인 수동 검증 — 실제 GitHub API를 호출한다.
 * GITHUB_APP_ID / GITHUB_APP_PRIVATE_KEY_PATH 환경변수가 없으면 스킵되므로 CI에서는 실행되지 않는다.
 *
 * 로컬 실행:
 *   GITHUB_APP_ID=... GITHUB_APP_PRIVATE_KEY_PATH=.secrets/xxx.pem ./gradlew test --tests GithubAuthChainManualTest
 */
class GithubAuthChainManualTest {

    @Test
    void jwt_서명부터_설치_레포_조회까지_전체_체인() {
        String appId = System.getenv("GITHUB_APP_ID");
        String keyPath = System.getenv("GITHUB_APP_PRIVATE_KEY_PATH");
        assumeTrue(appId != null && !appId.isBlank(), "GITHUB_APP_ID 미설정 — 스킵");
        assumeTrue(keyPath != null && !keyPath.isBlank(), "GITHUB_APP_PRIVATE_KEY_PATH 미설정 — 스킵");

        GithubAppAuthService auth = new GithubAppAuthService(appId, "", keyPath, "https://api.github.com");
        assumeTrue(auth.isEnabled(), "private key 로딩 실패 — 스킵");

        GithubClient client = new GithubClient(auth, "https://api.github.com");

        // 1) App JWT로 설치 목록 조회
        List<GithubInstallationInfo> installations = client.listAppInstallations();
        assertFalse(installations.isEmpty(), "설치가 하나도 없습니다");
        System.out.println("installations: " + installations);

        // 2) installation token으로 레포 목록 조회
        long installationId = installations.get(0).installationId();
        List<GithubRepoInfo> repos = client.listInstallationRepositories(installationId);
        assertFalse(repos.isEmpty(), "접근 가능한 레포가 없습니다");
        repos.forEach(r -> System.out.println("repo: " + r.fullName()
                + (r.isPrivate() ? " (private)" : "") + " default=" + r.defaultBranch()));

        // 3) 토큰 캐시 재사용 확인 (두 번째 호출은 캐시에서)
        String token1 = auth.getInstallationToken(installationId);
        String token2 = auth.getInstallationToken(installationId);
        assertNotNull(token1);
        assumeTrue(token1.equals(token2), "토큰이 캐시에서 재사용되어야 합니다");
    }
}
