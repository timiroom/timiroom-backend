# Timiroom Backend

LLM 기반 설계 자동화 플랫폼 — 백엔드 레포지토리

## 프로젝트 구조

```
timiroom-backend/
├── src/                    # 메인 백엔드 (Spring Boot)
├── rag-pipeline/           # RAG + 멀티 에이전트 파이프라인
│   ├── src/
│   ├── docker/             # DB 초기화 스크립트
│   └── docker-compose.yml  # 로컬 개발 인프라
└── README.md
```

## 기술 스택

| 분류 | 기술 |
|------|------|
| Backend | Spring Boot 3.3.4, Java 21 |
| AI/RAG | Spring AI 1.0.0-M3, OpenAI GPT-4o, text-embedding-3-large |
| Database | PostgreSQL 16 + pgvector, Neo4j |
| Messaging | Apache Kafka (Confluent 7.7.0) |
| Infra | QNAP NAS (Container Station), Docker |

## 팀 구성

| 이름 | GitHub | 역할 |
|------|--------|------|
| 하은현 | [@gkdmsgus](https://github.com/gkdmsgus) | BE / Infra |
| 임석현 | [@chunwol](https://github.com/chunwol) | BE |
| 이연호 | [@lyh030526](https://github.com/lyh030526) | BE / AI Pipeline |
| 김민정 | [@miiniminimo](https://github.com/miiniminimo) | FE |

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
```

### 환경변수

| 변수 | 설명 | 필수 |
|------|------|------|
| `OPENAI_API_KEY` | OpenAI API 키 | O |
| `COHERE_API_KEY` | Cohere Reranker API 키 | X |

## Git 브랜치 전략

- `main` — 안정 배포 브랜치
- `develop` — 개발 통합 브랜치
- `feat/#이슈번호-설명` — 기능 개발 브랜치
