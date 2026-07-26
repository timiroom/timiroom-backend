# GitHub App 설정 가이드

timiroom의 GitHub 연동(이슈 생성 / PR 정합성 / 브랜치 히스토리)은 GitHub App 방식으로 동작합니다.
이 문서는 App 정보와 로컬/운영 환경 설정 방법을 정리합니다.

## App 정보

| 항목 | 값 |
| --- | --- |
| App 이름 | timiroom |
| App ID | 4278317 |
| 소유 | timiroom 조직 |
| 공개 링크 | https://github.com/apps/timiroom |
| 관리 페이지 | 조직 Settings > Developer settings > GitHub Apps > timiroom |
| 권한 | Issues RW, Pull requests RW, Checks RW, Contents R, Metadata R |
| 웹훅 | https://timiroom.kro.kr/webhooks/github (이벤트: pull_request, push) — `pull_request`는 Phase 5 자동 정합성 review trigger로 수신 |

App 등록은 한 번으로 끝났고, 이후에는 사용할 조직/계정에 설치(Install)만 하면 됩니다.
조직에 설치하면 installation_id가 발급되고, 서버는 이 값으로 1시간짜리 installation token을 발급받아 GitHub API를 호출합니다.

## 로컬 개발 설정

1. private key(.pem)를 팀 내부에서 공유받아 프로젝트 루트의 `.secrets/` 폴더에 둡니다.
   `.secrets/`는 gitignore에 포함되어 있어 커밋되지 않습니다.
2. `.env`(또는 `.env.local`)에 아래 값을 추가합니다.

```
GITHUB_APP_ID=4278317
GITHUB_APP_PRIVATE_KEY_PATH=.secrets/timiroom.2026-07-12.private-key.pem
GITHUB_WEBHOOK_SECRET=(팀 공유 값)

# PR 정합성 Agent: EXAONE(국내) 또는 FOUNDRY(기존 해외) 중 선택
GITHUB_CONSISTENCY_AGENT_PROVIDER=EXAONE
GITHUB_CONSISTENCY_EXAONE_MODEL=LGAI-EXAONE/K-EXAONE-236B-A23B
```

`rag-pipeline/.env`에는 EXAONE 추론 서버를 지정합니다. vLLM·llama.cpp 자체 호스팅은
`EXAONE_API_KEY`를 비워둘 수 있고, 인증 프록시나 사내 게이트웨이는 Bearer 키를 설정합니다.

```dotenv
PR_CONSISTENCY_AGENT_PROVIDER=EXAONE
PR_CONSISTENCY_EXAONE_MODEL=LGAI-EXAONE/K-EXAONE-236B-A23B
EXAONE_CHAT_COMPLETIONS_URL=http://localhost:8000/v1/chat/completions
EXAONE_API_KEY=
```

기존 해외 모델로 전환하려면 백엔드와 rag-pipeline의 provider를 `FOUNDRY`로 바꾸고
`GITHUB_CONSISTENCY_FOUNDRY_MODEL`, `PR_CONSISTENCY_FOUNDRY_MODEL`, `FOUNDRY_API_KEY`를 설정합니다.

K-EXAONE 자체 호스팅 요구사항과 실행 명령은 [LG AI Research 공식 K-EXAONE 저장소](https://github.com/LG-AI-EXAONE/K-EXAONE)를
기준으로 합니다. 모델을 제3자 대상 상용 서비스 형태로 제공하는 경우에는 K-EXAONE 모델 라이선스의
별도 계약 조건을 배포 전에 확인해야 합니다.

값이 없으면 서버는 "GitHub App 미설정" 경고와 함께 정상 부팅되고, GitHub 관련 API만 비활성 상태가 됩니다.

## 운영(k8s) 설정

배포 워크플로우가 `APP_` 접두사 시크릿을 sealed secret으로 변환하므로, GitHub 저장소의
production environment에 아래 시크릿을 추가하면 됩니다.

| Secret 이름 | 값 |
| --- | --- |
| APP_GITHUB_APP_ID | 4278317 |
| APP_GITHUB_APP_PRIVATE_KEY | .pem 파일 내용 전체 (개행 포함) |
| APP_GITHUB_WEBHOOK_SECRET | 웹훅 시크릿 |
| APP_GITHUB_CONSISTENCY_AGENT_ENABLED | 전용 PR Consistency Agent 사용 여부 (기본 `true`, 실패 시 규칙 fallback) |
| APP_GITHUB_CONSISTENCY_AGENT_PROVIDER | `EXAONE` 또는 `FOUNDRY` (기본 `EXAONE`) |
| APP_GITHUB_CONSISTENCY_EXAONE_MODEL | LG 모델명 (기본 `LGAI-EXAONE/K-EXAONE-236B-A23B`) |
| APP_GITHUB_CONSISTENCY_FOUNDRY_MODEL | 기존 해외 모델명 (기본 `gpt-5.4-mini`) |

rag-pipeline 배포 시 production environment에는 `RAG_EXAONE_CHAT_COMPLETIONS_URL`,
필요한 경우 `RAG_EXAONE_API_KEY`, 그리고 `RAG_PR_CONSISTENCY_AGENT_PROVIDER=EXAONE`을 등록합니다.

운영에서는 파일 경로 대신 `GITHUB_APP_PRIVATE_KEY`에 PEM 내용을 직접 넣습니다.
이스케이프된 개행(\n)이 들어와도 코드에서 복원합니다.

## 동작 확인

로그인한 상태에서:

```
POST /api/v1/github/installations/sync        # GitHub에서 설치 목록을 가져와 DB 저장
GET  /api/v1/github/installations             # 저장된 설치 목록
GET  /api/v1/github/installations/{installationId}/repos   # 해당 설치가 접근 가능한 레포
```

또는 네트워크 호출 테스트를 직접 실행:

```
GITHUB_APP_ID=4278317 GITHUB_APP_PRIVATE_KEY_PATH=.secrets/xxx.pem ./gradlew test --tests GithubAuthChainManualTest
```

환경변수가 없으면 이 테스트는 스킵되므로 CI에는 영향이 없습니다.

## private key 재발급 / 폐기

키가 유출되었거나 교체가 필요하면 App 관리 페이지의 Private keys 섹션에서
새 키를 생성한 뒤 기존 키를 Delete 하면 됩니다. 여러 키가 동시에 유효할 수 있어
무중단 교체가 가능합니다. 교체 후 로컬 `.secrets/`와 운영 시크릿을 갱신하세요.

## 코드 구조 (Phase 0)

- `infra/github/GithubAppAuthService` — private key 로딩(PKCS#1/PKCS#8), App JWT 서명, installation token 발급·캐싱(만료 5분 전 갱신)
- `infra/github/GithubClient` — GitHub REST 호출 (설치 목록, 설치별 레포 목록)
- `domain/github/GithubInstallation` — 설치 엔티티 (installation_id, 계정, team 연결)
- `domain/github/GithubInstallationController` — 동기화/조회 API

Phase 1(프로젝트-레포 연결)·Phase 2(읽기 전용 브랜치 히스토리)·Phase 3(이슈 생성/조회)이 구현되었습니다. `pull_request` 웹훅의 opened/reopened/synchronize/ready_for_review 이벤트는 서명 검증 후 전용 PR Consistency Agent 검사를 실행하고 review comment와 Checks API 결과를 자동 게시합니다. 경고가 있으면 프로젝트 멤버에게 앱 알림도 생성하고, 이슈 참조 또는 feature branch 이름이 같은 열린 PR을 함께 표시합니다. Agent가 응답하지 않으면 규칙 엔진으로 자동 fallback합니다.
