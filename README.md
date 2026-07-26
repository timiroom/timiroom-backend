# Timiroom — Align-it

> AI 기반 프로덕트 협업 시스템 — 아이디어를 PRD · DB 스키마 · API 스펙으로 자동 생성

사용자의 서비스 아이디어를 채팅 형식의 폼으로 입력받아, RAG 파이프라인과 멀티 에이전트 워크플로우 오케스트레이션을 통해 PRD, DB 스키마, API 스펙을 자동 생성하는 AI 기반 프로덕트 협업 플랫폼입니다.

---

## 핵심 기능

| 기능 | 설명 |
|------|------|
| 채팅 기반 프로젝트 생성 | Claude AI와 대화하며 5단계 폼(플랫폼·기술스택·문제정의·타겟유저·기능정의)을 완성 |
| AI 실시간 추천 | 기술스택 / 페르소나 / MoSCoW 기능 추천을 Claude API로 비동기 처리 |
| RAG 파이프라인 | pgvector 하이브리드 검색 + Cohere Reranker로 관련 지식 추출 |
| 멀티 에이전트 오케스트레이션 | PM → PRD → DBA/API 병렬 → QA 순서로 에이전트가 협업하여 결과물 생성 |
| 자기강화 구조 | 생성된 결과물이 다음 파이프라인의 지식베이스로 자동 축적 |
| 버전 관리 (Commit) | 결과물 변경 시 Commit 단위로 이력 추적 및 롤백 |
| 표준 명세서 추출 | 확정된 설계를 Swagger(API), Mermaid(ERD) 등 현업 표준으로 자동 추출 |

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| **Backend** | Java 21, Spring Boot 3.5, Gradle |
| **AI & LLM** | Spring AI, OpenAI GPT-4o / GPT-4o-mini, Claude Sonnet 4, text-embedding-3-large |
| **RAG** | pgvector (HNSW), PostgreSQL FTS, Cohere Rerank, Semantic Chunking |
| **Data** | PostgreSQL + pgvector, Redis |
| **Async** | Kafka, Spring WebFlux, SSE |
| **Infra** | Docker, QNAP NAS, Nginx, Certbot (SSL) |
| **Frontend** | Next.js, React |

---

## 시스템 아키텍처

```
[프론트엔드 (Next.js)]
        |
        | 채팅 폼 (5단계) + Claude AI 추천
        ↓
[rag-pipeline (Spring Boot)]
        |
        ├── Phase 1: RAG 컨텍스트 생성
        │     FormToQueryService → QueryExpansion → HybridSearch → Reranker
        │
        ├── Phase 2: 멀티 에이전트 오케스트레이션
        │     SearchAgent → PmAgent → PrdAgent → DbaAgent/ApiAgent(병렬) → QaAgent
        │
        ├── Phase 3: 결과 검증 + 재시도
        │     ValidationService → RetryService (최대 3회) → Human-in-the-Loop
        │
        └── Phase 4: Kafka 이벤트 + 저장
              KafkaProducer → KafkaConsumer → document_chunks (자기강화)
```

---

## AI 추천 파이프라인

채팅 폼 진행 중 3개 시점에서 **Claude API를 비동기(prefetch)** 로 호출하여 대기 시간 없이 추천을 제공합니다.

| 시점 | 추천 내용 | API |
|------|---------|-----|
| 플랫폼 선택 완료 | 기술 스택 (프론트/백엔드/DB/인프라 파트별) | `POST /api/v1/recommendation/tech-stack` |
| Step 3 (PDF 업로드) 진입 | 타겟 유저 페르소나 2명 | `POST /api/v1/recommendation/persona` |
| Step 4 (타겟유저) 진입 | MoSCoW 기능 5~8개 | `POST /api/v1/recommendation/features` |

---

## 로컬 개발 환경 설정

### 1. 인프라 실행 (Docker)

```bash
# 프로젝트 루트에서 실행 — PostgreSQL, Redis, Neo4j, Kafka 전체 기동
docker compose up -d
```

> **주의:** Neo4j는 초기화에 10~20초 소요됩니다. 백엔드 기동 전 `docker ps`로 `healthy` 상태 확인 후 실행하세요.

### 2. 환경변수 설정

`.env` 파일은 `.gitignore`에 등록되어 있어 저장소에 포함되지 않습니다.  
**루트**와 **rag-pipeline/** 두 곳에 각각 생성해야 합니다.

---

#### `.env` (프로젝트 루트 — 백엔드 8080 용)

```dotenv
# PostgreSQL
DB_URL=jdbc:postgresql://localhost:5432/timiroom
DB_USERNAME=timiroom
DB_PASSWORD=timiroom1234

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# Neo4j
NEO4J_URI=bolt://localhost:7687
NEO4J_USERNAME=neo4j
NEO4J_PASSWORD=timiroom1234

# OAuth2 소셜 로그인 (Google Cloud Console / GitHub OAuth App 에서 발급)
GOOGLE_CLIENT_ID=your_google_client_id
GOOGLE_CLIENT_SECRET=your_google_client_secret
GITHUB_CLIENT_ID=your_github_client_id
GITHUB_CLIENT_SECRET=your_github_client_secret

# rag-pipeline 연동 URL
RAG_PIPELINE_URL=http://localhost:8081

# GitHub PR 정합성 Agent 제공자 선택
GITHUB_CONSISTENCY_AGENT_PROVIDER=EXAONE
GITHUB_CONSISTENCY_EXAONE_MODEL=LGAI-EXAONE/K-EXAONE-236B-A23B
```

로컬 OAuth 공급자 콘솔에는 아래 콜백 URL을 정확히 등록해야 합니다.

```text
Google: http://localhost:8080/login/oauth2/code/google
GitHub: http://localhost:8080/login/oauth2/code/github
```

#### `rag-pipeline/.env` (rag-pipeline 8081 용)

```dotenv
# AI API 키
OPENAI_API_KEY=your_openai_api_key        # GPT-4o 에이전트 + text-embedding-3-large
ANTHROPIC_API_KEY=your_anthropic_api_key  # Claude Sonnet (AI 추천 파이프라인)
COHERE_API_KEY=your_cohere_api_key        # Reranker (선택 — 없으면 reranker 비활성화)

# GitHub PR 정합성 Agent만 LG K-EXAONE으로 실행
PR_CONSISTENCY_AGENT_PROVIDER=EXAONE
PR_CONSISTENCY_EXAONE_MODEL=LGAI-EXAONE/K-EXAONE-236B-A23B
EXAONE_CHAT_COMPLETIONS_URL=http://localhost:8000/v1/chat/completions
EXAONE_API_KEY=                       # 자체 호스팅이면 비워둘 수 있음

# PostgreSQL (백엔드와 공유 DB)
DB_URL=jdbc:postgresql://localhost:5432/timiroom
DB_USERNAME=timiroom
DB_PASSWORD=timiroom1234
```

> API 키 발급은 이연호에게 문의하세요.

---

#### `application-local.yml` (선택)

`src/main/resources/application-local.yml` 및 `rag-pipeline/src/main/resources/application-local.yml`은 `.gitignore`에 등록된 로컬 전용 설정 파일입니다.  
`.env`로 커버되지 않는 로컬 오버라이드가 필요할 때만 사용합니다.

### 3. 백엔드 실행 (port 8080)

```bash
# 프로젝트 루트에서 실행
./gradlew bootRun
```

### 4. rag-pipeline 실행 (port 8081)

```bash
cd rag-pipeline
./gradlew bootRun
```

### 5. 프론트엔드 실행 (port 3000)

```bash
cd timiroom-frontend
npm install   # 최초 1회
npm run dev
```

브라우저에서 `http://localhost:3000` 으로 접속합니다.

---

## Git 협업 규칙

### 브랜치 전략 (Git Flow)

```
main          ← 배포/안정 버전 (직접 커밋 절대 금지)
  └── develop ← 팀원 작업이 모이는 통합 브랜치 (기본 브랜치)
       ├── feature/#이슈번호-작업내용   ← 새 기능 개발
       ├── fix/#이슈번호-버그내용       ← 버그 수정
       ├── docs/#이슈번호-내용          ← 문서 작업
       ├── refactor/#이슈번호-내용      ← 리팩토링
       └── hotfix/#이슈번호-내용        ← 긴급 버그 (main에서 분기)
```

> hotfix는 수정 후 **main과 develop 양쪽**으로 동시에 병합합니다.

---

### Issue 작성 규칙

코드 작업 전 **반드시 이슈를 먼저 등록**합니다. 이슈 하나 = 작업 단위 하나.

**제목 형식**

```
[feat] 새로운 기능 요약
[fix] 버그 내용 요약
[docs] 문서 작업 내용
[refactor] 리팩토링 내용
[hotfix] 긴급 버그 내용
```

**라벨 선택**

| 이슈 타입 | 라벨 |
|---------|------|
| `[feat]` | `enhancement` |
| `[fix]` | `bug` |
| `[docs]` | `documentation` |
| `[refactor]` | `refactor` |
| `[hotfix]` | `bug` |

**내용 작성 템플릿**

```markdown
## 작업 목적
무엇을 위해 하는 작업인지 한 줄로

## 작업 내용
- 구체적인 변경사항 1
- 구체적인 변경사항 2

## 작업 브랜치
feature/1-rag-pipeline
```

- **Assignees**: 반드시 본인 지정
- 이슈 번호(#1, #2 ...)는 브랜치·커밋·PR 제목에 모두 사용됩니다.

---

### Branch 규칙

**브랜치 네이밍**

| 이슈 타입 | 브랜치 접두사 | 예시 |
|---------|------------|------|
| `[feat]` | `feature/` | `feature/1-rag-pipeline` |
| `[fix]` | `fix/` | `fix/7-pdf-parsing-error` |
| `[docs]` | `docs/` | `docs/12-readme-update` |
| `[refactor]` | `refactor/` | `refactor/23-agent-cleanup` |
| `[hotfix]` | `hotfix/` | `hotfix/99-ssl-fix` |

**브랜치 생성 방법**

```bash
# 1. 작업 시작 전 develop 최신화 (필수!)
git switch develop
git pull origin develop

# 2. git branch 로 * develop 확인 후 브랜치 생성
git switch -c feature/1-rag-pipeline

# 3. 작업 완료 후 push
git push origin feature/1-rag-pipeline
```

> ⚠️ feature 브랜치는 반드시 **develop에서** 만들어야 합니다.
> 브랜치 생성 전 `git branch`로 `* develop` 확인 필수!

---

### Commit 메시지 규칙

**형식**: `타입: 작업 내용 요약 (#이슈번호)`

| 타입 | 설명 | 예시 |
|------|------|------|
| `feat` | 새로운 기능 | `feat: RAG 파이프라인 Phase1 구현 (#1)` |
| `fix` | 버그 수정 | `fix: PDF 파싱 InputStream 오류 수정 (#7)` |
| `docs` | 문서 수정 | `docs: README 협업 규칙 추가 (#12)` |
| `refactor` | 코드 구조 개선 | `refactor: AgentService 레이어 분리 (#23)` |
| `chore` | 설정, 의존성 변경 | `chore: application.yml 환경 분리` |
| `test` | 테스트 코드 | `test: HybridSearchService 단위 테스트 추가` |
| `style` | CSS 등 스타일만 수정 | `style: 버튼 hover 색상 변경` |

```bash
git add .
git commit -m "feat: FormToQueryService 구현 (#3)"
```
---

### PR (Pull Request) 규칙

**제목 형식**

```
[feat] 기능명 (#이슈번호)
[fix] 버그명 (#이슈번호)
[docs] 내용 (#이슈번호)
```

**내용 템플릿**

```markdown
## 작업 내용
- 변경사항 1
- 변경사항 2

## 변경 이유
왜 이 작업을 했는지

## 테스트 방법
어떻게 테스트하면 되는지

## 관련 이슈
Closes #이슈번호
```

> `Closes #이슈번호` 를 반드시 작성하세요.
> PR이 머지되면 해당 이슈가 자동으로 닫힙니다. (`Fixes #N`, `Resolves #N`도 동일 효과)

**PR 생성 전 체크리스트**

- [ ] base 브랜치가 **develop** 인지 확인 (`base: develop ← compare: feature/...`)
- [ ] 제목 형식 준수 (`[타입] 내용 (#이슈번호)`)
- [ ] `Closes #이슈번호` 작성
- [ ] Assignees 본인 지정

**머지 규칙**

- `main`, `develop` 브랜치에 직접 push 금지
- feature 브랜치 → develop PR (팀원 **3명 승인** 필요)
- 새 커밋 push 시 기존 승인 자동 취소
- 머지 후 브랜치 자동 삭제

---

### 이슈 → 브랜치 → 커밋 → PR 타입 통일 규칙

| 이슈 타입 | 브랜치 접두사 | 커밋 타입 | PR 제목 | base |
|---------|------------|---------|---------|------|
| `[feat]` | `feature/` | `feat:` | `[feat]` | `develop` |
| `[fix]` | `fix/` | `fix:` | `[fix]` | `develop` |
| `[docs]` | `docs/` | `docs:` | `[docs]` | `develop` |
| `[refactor]` | `refactor/` | `refactor:` | `[refactor]` | `develop` |
| `[hotfix]` | `hotfix/` | `hotfix:` | `[hotfix]` | `main + develop` |

---

### 매 작업마다 반복하는 전체 흐름

```
① develop 최신화 (작업 시작 전 필수!)
   git switch develop
   git pull origin develop

② Issue 등록 (GitHub Issues 탭)
   제목: [feat] 작업내용 | 라벨 선택 | Assignees: 본인

③ feature 브랜치 생성 (develop에서 분기!)
   git branch  ← * develop 확인 후
   git switch -c feature/이슈번호-작업명

④ 코드 작업

⑤ Commit
   git add .
   git commit -m "feat: 작업내용 요약 (#이슈번호)"

⑥ Push
   git push origin feature/이슈번호-작업명

⑦ PR 생성 — base: develop 확인 필수!
   제목: [feat] 작업내용 (#이슈번호)
   내용 마지막: Closes #이슈번호

⑧ 코드 리뷰 & Merge → develop으로 병합 (3명 승인)

⑨ develop 최신화
   git switch develop
   git pull origin develop

🔄 ①로 돌아가서 반복
```

---

## 팀 구성

| 역할 | 이름 | 담당 |
|------|------|------|
| BE1 | 이연호 | AI 파이프라인 (RAG, 멀티 에이전트, Claude 추천) |
| BE2 | 하은현 | DB 설계 (PostgreSQL, pgvector, Redis, Kafka) |
| BE3 | 정용환 | 서버 아키텍처 (REST API, SSE, JWT, 배포) |
| PM + FE | 김민정 | 기획 총괄 + Next.js 프론트엔드 |
| Infra | 임석현 | NAS 서버, 도메인, SSL |
| FE | 심민식 | Next.js 프론트엔드 |

---

## 파일 구조
- [Notion — 파일 구조](https://www.notion.so/Timiroom-35933f97d4d5816ba701f760a989b7c6)
