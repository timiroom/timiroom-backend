# Timiroom Backend

## 프로젝트 구조 (Notion 링크 참고)
https://www.notion.so/Timiroom-35933f97d4d5816ba701f760a989b7c6?source=copy_link

### 사전 요구사항
- Java 21
- Docker Desktop (WSL2)
- Gradle 9.x

### 실행 방법

```bash
# 1. 백엔드 실행
1-1. intellij실행후 터미널 키기
1-2. docker compose up -d 입력

# 2. 프론트엔드 실행
2-1. FrontEnd Repo에서 feat/#3_FrontEnd_Link에서 받은 파일 열기
2-2. 터미널에서 npm run dev 입력후 실행
```

### 환경변수
키 코드 없으신분들은 '이연호'한테 카톡하시면 됩니다.
| 변수 | 설명 | 필수 |
|------|------|------|
| `OPENAI_API_KEY` | OpenAI API 키 | O |
| `ANTHROPIC_API_KEY` | Claude API 키 | O |


