# Timiroom Backend

> LLM 기반 요구사항 자동 설계 플랫폼 — 자연어 입력 → ERD + API 명세 자동 생성

---

## 프로젝트 구조
```
timiroom-backend/
├── rag-pipeline/           # RAG + 멀티 에이전트 AI 파이프라인
│   ├── src/                # Spring Boot 애플리케이션
│   ├── docker/             # DB 초기화 스크립트
│   └── docker-compose.yml  # 로컬 개발 인프라 (PostgreSQL, Kafka)
└── README.md
```

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.3.4, Spring AI 1.0.0-M3, LangGraph4j 1.5.0 |
| AI | OpenAI GPT-4o, GPT-4o-mini, text-embedding-3-large |
| Database | PostgreSQL 16 + pgvector (HNSW), Neo4j |
| Messaging | Apache Kafka 3.7.1 |
| Infra | Docker Compose, QNAP NAS (Container Station) |

---

## 팀 구성

| 이름 | GitHub | 역할 |
|------|--------|------|
| 하은현 | [@gkdmsgus](https://github.com/gkdmsgus) | BE / Infra |
| 임석현 | [@chunwol](https://github.com/chunwol) | BE |
| 이연호 | [@lyh030526](https://github.com/lyh030526) | BE / AI Pipeline |
| 김민정 | [@miiniminimo](https://github.com/miiniminimo) | FE |
| 정용환 | [@wjddydghks](https://github.com/wjddydghks) | BE |
---

## 로컬 개발 환경

### 사전 요구사항

- Java 21
- Docker Desktop (WSL2)
- Gradle 9.x

### 실행 방법
```bash
# 1. 인프라 컨테이너 기동
cd rag-pipeline
docker compose up -d

# 2. 앱 실행
./gradlew bootRun

# 인프라 종료
docker compose down
```

### 환경변수 설정

| 변수 | 설명 | 필수 |
|------|------|:----:|
| `OPENAI_API_KEY` | OpenAI API 키 | ✅ |
| `COHERE_API_KEY` | Cohere Reranker API 키 | ❌ |

---

## Git 브랜치 전략

### 브랜치 네이밍
```
feat/#이슈번호-기능명     # 새 기능
fix/#이슈번호-버그명      # 버그 수정
refactor/#이슈번호-내용  # 리팩토링
chore/#이슈번호-내용     # 설정, 환경
docs/#이슈번호-내용      # 문서 작업
```

### 커밋 컨벤션
```
feat:     새로운 기능 추가
fix:      버그 수정
refactor: 코드 리팩토링
chore:    설정, 의존성 변경
docs:     문서 수정
test:     테스트 코드
```

### PR 규칙

- `main` 브랜치 직접 push **불가**
- PR 생성 후 **3명 승인** 필요
- 새 커밋 push 시 기존 승인 자동 취소
- 머지 후 브랜치 자동 삭제

### PR 제목 형식
```
[FEATURE/#1] 기능명
[FIX/#12] 버그명
[REFACTOR/#23] 내용
```
```

---