# Timiroom Backend

> LLM 기반 기획-개발 정합성 보장 및 영향 범위 시각화 플랫폼

자연어 요구사항을 입력하면 API 명세서 + ERD를 자동 생성하고, 변경 시 영향 범위를 시각화해주는 서비스입니다.

---

## 핵심 기능

| 기능 | 설명 |
|------|------|
| 자연어 기반 설계 자동화 | 사용자의 요구사항을 LLM이 분석하여 기능 명세, API 설계, DB 스키마를 자동 생성 |
| 인터랙티브 지식 그래프 | 기획-API-DB 간 연결 고리를 Knowledge Graph로 시각화하여 의존성 파악 |
| 정합성 검증 | 설계 변경이 시스템 전체에 미치는 영향을 시뮬레이션하여 잠재적 오류 사전 탐지 |
| 버전 관리 | 설계 변경 이력을 스냅샷으로 저장하여 롤백 및 비교 기능 제공 |
| 표준 명세서 추출 | 확정된 설계를 Swagger(API), Mermaid(ERD) 등 현업 표준 규격으로 자동 추출 |

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| **Backend Core** | Java 21, Spring Boot 3.5, Gradle |
| **AI & LLM** | LangChain4j, LangGraph4j, OpenAI GPT-4o, text-embedding-3-large |
| **Data** | PostgreSQL + pgvector (RDB + RAG), Neo4j (Graph DB), Redis (Cache) |
| **Security** | Spring Security, JWT |
| **Async & Streaming** | Spring WebFlux, SSE, Kafka |
| **Infra** | QNAP NAS (Docker), Nginx, Certbot (SSL) |
| **Documentation** | SpringDoc (Swagger / OpenAPI), Mermaid.js |
| **Collaboration** | GitHub, Notion |

---

## 서버 아키텍처

> 추후 이미지 추가 예정

```
Client (Next.js) → Nginx (SSL/Reverse Proxy) → Spring Boot API (REST/SSE)
                                                      ↓
                                              LangGraph4j 오케스트레이터
                                              ↓           ↓           ↓
                                          PM 에이전트  DBA 에이전트  API 에이전트
                                              ↓           ↓           ↓
                                          설계 검증 엔진 → 버전 관리 모듈
                                              ↓           ↓           ↓
                                          PostgreSQL    Neo4j       Redis
```

---

## ERD

> 추후 추가 예정 (월요일 DB 설계 후 반영)

---

## 프로젝트 구조 (DDD)

```
src/main/java/com/timiroom/backend/
├── domain/
│   ├── user/                  # 사용자/인증
│   │   ├── controller/
│   │   ├── service/
│   │   ├── repository/
│   │   ├── entity/
│   │   ├── dto/
│   │   └── exception/
│   ├── project/               # 프로젝트 관리
│   ├── pipeline/              # AI 파이프라인
│   ├── artifact/              # 생성 결과물 (API 명세, ERD)
│   └── graph/                 # Neo4j 지식 그래프
├── global/
│   ├── config/                # 공통 설정
│   ├── response/              # 통일 응답 포맷
│   ├── exception/             # 에러 코드, 핸들러
│   ├── security/              # JWT, 인증/인가
│   └── external/              # 외부 API 연동 (OpenAI)
└── TimiroomApplication.java
```

---

## 브랜치 전략

### 브랜치 구조

```
main          ← 배포/안정 버전 (항상 정상 동작)
  └── develop ← 개발 통합 브랜치 (기능 합치는 곳)
       ├── feat/#이슈번호-기능명
       ├── fix/#이슈번호-버그명
       └── refactor/#이슈번호-내용
```

### 브랜치 네이밍

```
feat/#이슈번호-기능명       # 새 기능
fix/#이슈번호-버그명        # 버그 수정
refactor/#이슈번호-내용     # 리팩토링
chore/#이슈번호-내용        # 설정, 환경 등
docs/#이슈번호-내용         # 문서 작업
hotfix/#이슈번호-내용       # 긴급 버그 수정 (main에서 분기)
```

### 예시

```
feat/#1-user-entity
fix/#12-jwt-token-expiry
refactor/#23-agent-pipeline
hotfix/#50-ssl-connection-fix
```

---

## PR 규칙

- `main`, `develop` 브랜치에 직접 push 불가
- 기능 브랜치 → `develop`으로 PR (팀원 **3명 승인** 필요)
- `develop` → `main`은 배포 시점에 머지
- 새 커밋 push 시 기존 승인 자동 취소
- 머지 후 브랜치 자동 삭제

### PR 제목 형식

```
[FEATURE/#1] 기능명
[FIX/#12] 버그명
[REFACTOR/#23] 내용
[CHORE/#30] 내용
[DOCS/#35] 내용
```

---

## 작업 흐름

```
1. GitHub Issue 생성
2. 이슈 번호 확인 후 develop에서 브랜치 생성
   git checkout develop
   git checkout -b feat/#이슈번호-기능명
3. 작업 및 커밋
4. develop으로 PR 생성 (제목 형식 준수)
5. 팀원 3명 코드 리뷰 및 승인
6. develop 머지 → 브랜치 자동 삭제
7. 배포 시 develop → main 머지
```

---

## 커밋 메시지 컨벤션

```
feat: 새로운 기능 추가
fix: 버그 수정
refactor: 코드 리팩토링
chore: 설정, 의존성 변경
docs: 문서 수정
test: 테스트 코드
```

### 예시

```
feat: User 엔티티 추가
fix: JWT 토큰 만료 오류 수정
chore: application.yml 환경 분리 (local/prod)
```

---

## 로컬 개발 환경 설정

### 1. DB 실행

```bash
docker-compose up -d
```

PostgreSQL (5432), Neo4j (7474/7687), Redis (6379) 컨테이너 실행

### 2. 앱 실행

```bash
./gradlew bootRun
```

기본 프로필이 `local`이므로 localhost DB에 자동 연결

### 3. NAS 서버 연결 (배포용)

```bash
./gradlew bootRun --args='--spring.profiles.active=prod'
```

---

## 팀 구성

| 역할 | 이름 | GitHub | 담당 |
|------|------|--------|------|
| BE1 | 이연호 | [@lyh030526](https://github.com/lyh030526) | AI 파이프라인 (LangChain4j, RAG, 멀티 에이전트) |
| BE2 | 하은현 | [@miiniminimo](https://github.com/miiniminimo) | DB 설계 (PostgreSQL, Neo4j, Redis, Event Sourcing) |
| BE3 | 정용환 | | 서버 아키텍처 (REST API, SSE, JWT, 배포) |
| PM + FE | 김민정 | | 기획 총괄 + React 프론트엔드 |
| Infra | 임석현 | | NAS 서버 세팅, 도메인, SSL |
