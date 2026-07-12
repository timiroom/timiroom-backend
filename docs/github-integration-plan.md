# GitHub 연동 기능 설계 및 실행 계획

> 목표: 프로젝트에 GitHub 레포지토리를 연결하고 **이슈 생성 · PR 정합성 확인 · 브랜치 히스토리 관리**를 제공한다.
> 한 프로젝트가 여러 레포(backend / frontend / pipeline / ops)에 걸치는 구조를 1급으로 지원한다.

---

## 1. 확정된 방향

| 항목 | 결정 | 비고 |
|---|---|---|
| 인증 방식 | **GitHub App** | repo 단위 설치, 웹훅 내장, 높은 rate limit, 봇 정체성 |
| PR 정합성 정의 | **① 명세 대조 + ② repo 간 정합성** | ①이 이 제품의 차별점 (생성한 API_SPEC/DB_SCHEMA와 실제 코드 대조) |
| 멀티 repo | `project 1 : N repo` 조인 | timiroom 자체가 backend+frontend+pipeline+ops = repo 4개 |
| 실행 방식 | 계획 → Phase별 이슈/브랜치 생성 후 순서대로 | 이 문서가 로드맵 |

---

## 2. 멀티 레포 모델 (핵심 설계)

한 프로젝트 = 여러 GitHub repo. 기능별로 단위가 다르다.

- **이슈 생성 / 브랜치 히스토리** → **repo 단위** (어느 repo에 이슈를 팔지 선택)
- **PR 정합성** → **프로젝트 단위** (연결된 repo들의 PR을 교차 확인)

```
Project "timiroom"
├─ timiroom-backend    (role_hint: BACKEND)
├─ timiroom-frontend   (role_hint: FRONTEND)
├─ timiroom-pipeline_py(role_hint: PIPELINE)
└─ timiroom-ops        (role_hint: INFRA)
```

---

## 3. 데이터 모델

### 3.1 신규 엔티티

```
github_installation                     -- GitHub App 설치 단위
├─ id                (PK)
├─ installation_id   (BIGINT, unique)   -- GitHub이 발급
├─ account_login     (VARCHAR)          -- "timiroom"
├─ account_type      (VARCHAR)          -- Organization | User
├─ team_id           (FK, nullable)     -- 어느 워크스페이스에 연결
└─ created_at / updated_at

github_repo                             -- 설치에 포함된 repo
├─ id                (PK)
├─ github_repo_id    (BIGINT, unique)   -- GitHub repo id
├─ full_name         (VARCHAR)          -- "timiroom/timiroom-backend"
├─ default_branch    (VARCHAR)          -- "develop"
├─ private           (BOOLEAN)
├─ installation_id   (FK → github_installation)
└─ created_at / updated_at

project_repository                      -- Project ↔ GithubRepo (1:N)
├─ id                (PK)
├─ project_id        (FK → project)
├─ github_repo_id    (FK → github_repo.id)
├─ role_hint         (VARCHAR, nullable)-- BACKEND | FRONTEND | PIPELINE | INFRA
└─ created_at
   UNIQUE(project_id, github_repo_id)
```

### 3.2 토큰 처리

GitHub App은 **유저 토큰을 저장하지 않는다**. 대신:

1. App **private key(.pem)** 로 JWT 서명 (앱 인증)
2. JWT → `installation access token` 발급 (repo 작업용, **1시간 만료**)
3. installation token은 **메모리 캐싱**(만료 5분 전 갱신), DB 저장 불필요

> 저장이 필요한 비밀은 App ID · private key · webhook secret 뿐 → k8s sealed-secret (`APP_` prefix).

---

## 4. 백엔드 구성

### 4.1 패키지 구조 (신규 `domain/github/`)

```
domain/github/
├─ GithubInstallation.java        (엔티티)
├─ GithubRepo.java                (엔티티)
├─ ProjectRepository.java         (엔티티 — 조인) ※ JPA Repo와 이름 충돌 주의 → ProjectRepoLink 로 명명
├─ *Repository.java               (JPA 리포지토리들)
├─ GithubInstallationController   (설치 콜백/조회)
├─ RepoLinkController             (프로젝트-repo 연결 CRUD)
├─ IssueController                (이슈 생성/조회)
├─ PullRequestController          (PR 조회/정합성)
├─ BranchController               (브랜치·커밋 히스토리)
├─ WebhookController              (GitHub 이벤트 수신)
└─ service/
   ├─ GithubAppAuthService        (JWT 서명 → installation token 캐싱)
   ├─ RepoLinkService
   ├─ IssueService
   ├─ PullRequestService
   ├─ BranchService
   └─ ConsistencyCheckService     (명세 대조 + repo 간)

infra/github/
└─ GithubClient.java              (WebClient 래퍼 — RagPipelineClient 패턴 踏襲)
```

### 4.2 API 엔드포인트

| Method | Path | 설명 |
|---|---|---|
| GET | `/api/v1/github/install-url?teamId=` | App 설치 페이지 URL 생성 |
| GET | `/api/v1/github/installations/callback` | 설치 후 콜백 (installation_id 저장) |
| GET | `/api/v1/github/installations/{teamId}/repos` | 설치에 포함된 선택 가능 repo 목록 |
| GET | `/api/v1/projects/{projectId}/repos` | 프로젝트에 연결된 repo 목록 |
| POST | `/api/v1/projects/{projectId}/repos` | repo 연결 `{githubRepoId, roleHint}` |
| DELETE | `/api/v1/projects/{projectId}/repos/{repoId}` | 연결 해제 |
| GET | `/api/v1/projects/{projectId}/repos/{repoId}/branches` | 브랜치 목록 |
| GET | `/api/v1/projects/{projectId}/repos/{repoId}/commits?branch=` | 커밋 히스토리 |
| GET | `/api/v1/projects/{projectId}/issues` | 이슈 목록(연결 repo 통합) |
| POST | `/api/v1/projects/{projectId}/issues` | 이슈 생성 `{repoId, title, body, labels}` |
| GET | `/api/v1/projects/{projectId}/pulls` | PR 목록(연결 repo 통합) |
| POST | `/api/v1/projects/{projectId}/pulls/{repoId}/{number}/check` | 정합성 검사 실행 |
| POST | `/webhooks/github` | 웹훅 수신 (permitAll + 서명 검증) |

### 4.3 SecurityConfig 변경

- `/webhooks/github` → `permitAll` (HMAC 서명으로 자체 검증)
- 나머지 `/api/v1/github/**`, `/api/v1/projects/**/{issues,pulls,repos}` → `authenticated`

---

## 5. PR 정합성 검사 설계

### 5.1 명세 대조 (①)
1. PR의 변경 파일 diff 조회 (`GET /repos/{o}/{r}/pulls/{n}/files`)
2. 프로젝트의 최신 `API_SPEC` / `DB_SCHEMA` artifact 로드 (기존 `PipelineArtifact`)
3. 대조 판정:
   - **API_SPEC**: 명세에 정의된 엔드포인트/필드가 실제 컨트롤러·DTO diff와 일치하는지
   - **DB_SCHEMA**: 명세 테이블/컬럼이 엔티티·마이그레이션 diff와 일치하는지
4. 판정 엔진: 1차 규칙 기반 → 2차 rag-pipeline(LLM) 위임 검토 (기존 파이프라인 재활용)
5. 결과를 GitHub **Checks API**로 PR에 게시 + 앱 내 배지 표시

### 5.2 repo 간 정합성 (②)
- 연결된 repo들의 열린 PR을 조회, 라벨/브랜치 네이밍/이슈 참조로 **연관 PR 그룹** 추론
- 예: backend가 API 시그니처를 바꾼 PR ↔ frontend가 그 API를 소비하는 PR이 **짝으로 머지돼야 함**을 경고
- MVP: 연관 PR을 "함께 봐야 할 세트"로 묶어 표시 → 이후 실제 diff 교차 분석으로 고도화

---

## 6. 프론트엔드 구성

| 위치 | 기능 |
|---|---|
| 워크스페이스 설정 | **"GitHub 연결"** 섹션 — App 설치 버튼 → 콜백 후 repo 선택 |
| 프로젝트 설정 | 연결된 repo 목록 관리 (role_hint 지정) |
| ActivityBar "커밋 히스토리" 탭 | **브랜치·커밋 타임라인** 뷰로 채움 (현재 placeholder) |
| 프로젝트 뷰 | **Issues 탭** (목록 + 생성 모달) |
| 프로젝트 뷰 | **PRs 탭** (목록 + 정합성 결과) |
| 명세 패널(PRD/API/ERD) | 정합성 **배지** (명세 vs 실제 코드 일치 여부) |

기존 4패널 레이아웃과 teamApi/projectApi 패턴을 그대로 확장.

---

## 7. 실행 로드맵 (Phase)

각 Phase마다 이슈와 브랜치를 따로 파서 해당 브랜치에서 작업한다. 표기: (수동)은 사람이 직접, (백엔드)/(프론트)는 코드 작업.

### Phase 0 — GitHub App 인프라 (선행 필수)
- (수동) GitHub App 등록 — 권한: Issues RW, Pull requests RW, Contents R, Metadata R, Checks RW / 웹훅: PR, Push
- (수동) private key(.pem), App ID, webhook secret을 secret으로 등록
- (백엔드) `GithubInstallation` 엔티티 + 설치 콜백 처리
- (백엔드) `GithubAppAuthService` — JWT 서명 → installation token 캐싱
- (백엔드) `GithubClient` (WebClient) 골격 + 헬스 체크용 호출

### Phase 1 — Repo 연결 (의존: P0)
- (백엔드) `GithubRepo` / `ProjectRepoLink` 엔티티 + 마이그레이션
- (백엔드) 설치 repo 목록 조회 / 프로젝트-repo 연결 CRUD
- (프론트) 워크스페이스·프로젝트 설정에 repo 연결 UI

### Phase 2 — 브랜치 히스토리 (의존: P1, 읽기 전용)
- (백엔드) 브랜치 목록 / 커밋 히스토리 조회
- (프론트) "커밋 히스토리" 탭을 브랜치·커밋 타임라인으로

### Phase 3 — 이슈 생성/조회 (의존: P1)
- (백엔드) 이슈 목록(연결 repo 통합) / 생성
- (프론트) 프로젝트 Issues 탭 + 생성 모달

### Phase 4 — PR 정합성 (의존: P1, P3 / 핵심 차별점)
- (백엔드) PR 목록·상세 조회
- (백엔드) 명세 대조 엔진 (규칙 기반 → rag-pipeline LLM 위임)
- (백엔드) repo 간 연관 PR 그룹핑
- (백엔드) Checks API 게시
- (프론트) PRs 탭 + 명세 패널 배지

### Phase 5 — 웹훅 + 알림 (의존: P4)
- (백엔드) `/webhooks/github` 수신 + HMAC 검증
- (백엔드) PR opened/synced 시 정합성 자동 트리거
- (백엔드) 결과를 기존 `notification` 도메인에 연동

---

## 8. 열린 질문 / 리스크

- installation과 team 매핑: 한 조직 설치를 여러 워크스페이스가 공유할지, 워크스페이스당 설치할지
- 명세 대조 정확도: 규칙 기반의 한계와 LLM 위임 시 비용/지연 트레이드오프
- rate limit: 대량 PR 검사 시 배치·캐싱 전략
- private repo(ops): 권한 범위 및 노출 정책
