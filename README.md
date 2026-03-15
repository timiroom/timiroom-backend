# Timiroom Backend

> LLM 기반 기획-개발 정합성 보장 및 영향 범위 시각화 플랫폼

자연어 요구사항을 입력하면 API 명세서 + ERD를 자동 생성하고, 변경 시 영향 범위를 시각화해주는 서비스입니다.

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.5 |
| Build | Gradle |
| DB | PostgreSQL, Neo4j, Redis |
| AI | LangChain4j, GPT-4o |
| 문서 | SpringDoc (Swagger) |

---

## 브랜치 전략

### 브랜치 네이밍
```
feat/#이슈번호-기능명       # 새 기능
fix/#이슈번호-버그명        # 버그 수정
refactor/#이슈번호-내용     # 리팩토링
chore/#이슈번호-내용        # 설정, 환경 등
docs/#이슈번호-내용         # 문서 작업
```

### 예시
```
feat/#1-user-entity
fix/#12-jwt-token-expiry
refactor/#23-agent-pipeline
```

---

## PR 규칙

- `main` 브랜치에 직접 push 불가
- PR 생성 후 **3명 승인**이 있어야 머지 가능
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
2. 이슈 번호 확인 후 브랜치 생성
   git checkout -b feat/#이슈번호-기능명
3. 작업 및 커밋
4. PR 생성 (제목 형식 준수)
5. 팀원 3명 코드 리뷰 및 승인
6. main 머지 → 브랜치 자동 삭제
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
```

---

## 팀 구성

| 역할 | 이름 | 담당 |
|------|------|------|
| BE1 | 이연호 | AI 파이프라인 (LangChain4j, RAG, 멀티 에이전트) |
| BE2 | 하은현 | DB 설계 (PostgreSQL, Neo4j, Redis, Event Sourcing) |
| BE3 | 정용환 | 서버 아키텍처 (REST API, SSE, JWT, 배포) |
| PM + FE | 김민정 | 기획 총괄 + React 프론트엔드 |
