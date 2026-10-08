# AI 도구와 Slack 연결

현재 feature 브랜치의 연결 기능이다. develop에 병합·배포하지 않았다. 공개 서버에서 바로 사용할 수 있는 상태로 표시하지 않는다. 운영자의 배포 승인과 실제 Slack 설정·검증이 별도로 필요하다.

## 서버 설정

Java 21 / PostgreSQL / Redis를 사용한다. `integration.enabled` 기본값은 false다. 아래 설정을 격리 환경의 외부 설정으로 제공한다. issuer는 실제 공개 API의 HTTPS origin이며 path·query·fragment가 없어야 한다. localhost 테스트만 HTTP를 허용한다.

```yaml
integration:
  enabled: true
  issuer: https://YOUR_API_HOST
  cursor-secret: ${INTEGRATION_CURSOR_SECRET}
  oauth:
    codex:
      redirect-uri: http://127.0.0.1:56381/callback
    claude:
      redirect-uri: http://localhost:56382/callback
  slack:
    enabled: false
```

cursor-secret은 32바이트 이상인 무작위 비밀 값이다. 파일·저장소·로그·스킬에 실값을 넣지 않는다. 기존 문서 수정·영향 분석·정합성 서비스 주소와 인증 설정은 기존 엔진 설정을 그대로 사용한다. 전용 개발 AI 모델을 새로 운영하지 않는다.

MCP 전송은 stateless HTTP다. 연결마다 특정 서버 메모리에 의존하지 않으며 백엔드 replica들은 같은 PostgreSQL·Redis·issuer·cursor-secret을 사용한다. 매 요청마다 Bearer 토큰과 현재 권한을 검사한다. 웹 세션 쿠키가 함께 오더라도 MCP 인증이 웹 로그인 세션 ID를 회전시키지 않으며, 쿠키만으로 MCP에 접근할 수 없다. 일반 OAuth 웹 로그인 체인의 세션 보호는 유지한다.

프론트는 `NEXT_PUBLIC_INTEGRATION_ENABLED=true`로 연결·승인 화면을 켠다. Slack 화면은 `NEXT_PUBLIC_SLACK_ENABLED=true`로 켠다. Docker build ARG와 배포 workflow의 같은 이름 GitHub variable을 지원하며 기본값은 false다. 값은 빌드할 때 반영되므로 서버 기능을 먼저 구성하고 프론트를 다시 빌드한다. 최초 기준 발행 전에는 기존 문서 편집을 사용하며, PM이 기준을 발행한 뒤 저장은 변경안→교차검증→승인으로 처리한다. 발행된 문서의 기존 저장 API는 직접 변경을 거절한다.

프로젝트의 `변경안·검사 기록`에서 화면을 닫은 뒤에도 변경안과 검사 작업을 다시 열 수 있다. 페이지당 20개이며 검사 링크는 해당 작업 당시의 snapshot/revision/head 결과를 보여 준다. 프로젝트 문서 읽기 권한을 가진 팀원은 웹에서 결과를 공유하고, MCP의 작업 조회는 여전히 요청자 본인과 승인된 scope로 제한한다. 로그인 전 링크를 열면 로그인 후 같은 경로로 복귀한다. MCP 로그인도 기존 계정에 맞게 Google 또는 GitHub를 선택한다.

변경안 화면은 외부 AI나 Slack이 요청한 문서 검사 완료를 자동으로 반영한다. 발행 기준이 바뀐 미승인 변경안은 오래된 기준임을 표시하고 승인을 막는다. `이 변경안 보완하기`는 수정 후 문서를 이어 사용해 별도의 변경안을 만든다. 원래 변경안을 덮어쓰지 않으며 새 변경안에도 문서 교차검증과 PM 승인이 필요하다.

문서 승인 검증은 consistency 엔진의 `inputComplete: true` 응답을 요구한다. 기존 엔진과 새 백엔드가 섞이면 승인 검증이 실패하도록 처리한다. 전체 산출물 컨텍스트가 55,000자를 넘으면 잘라서 PASS를 만들지 않고 검사를 거절한다. 문서 범위를 축소하거나 전체 입력을 지원하는 검증 방식이 필요하다. 검사 시작 MCP 도구는 접수 정보만 반환하며 판정 상세 조회는 `consistency:read`가 필요하다. PM 화면에는 같은 변경안 revision/hash에 묶인 최신 완료 검사의 요약·근거·권장 수정을 표시한다.

## Codex

운영자가 확정한 주소로 등록한다.

```powershell
codex mcp add timiroom --url https://YOUR_API_HOST/mcp --oauth-client-id timiroom-codex
```

기존 config.toml의 해당 서버 oauth 항목에 아래 값을 병합한다. 기존 설정 파일 전체를 덮어쓰지 않는다.

```toml
[mcp_servers.timiroom.oauth]
client_id = "timiroom-codex"
callback_url = "http://127.0.0.1:56381/callback"
callback_port = 56381
```

`codex mcp login timiroom`으로 로그인한다. 위 URI와 서버에 등록된 URI가 정확히 같아야 한다. OAuth resource는 MCP 보호 자원 metadata에서 제공하므로 같은 값을 별도로 중복 설정할 필요가 없다. 실제 Codex CLI 0.159.3에서 격리 Spring AS/PostgreSQL/Redis 로그인과 명세 읽기를 확인했다. 공개 서버·최초 사용자 설치 전체 검증은 별도다.

## Claude Code

```powershell
claude mcp add --transport http --client-id timiroom-claude --callback-port 56382 timiroom https://YOUR_API_HOST/mcp
```

Claude Code의 `/mcp`에서 Timiroom을 선택해 Authenticate한다. 로그인 화면에서 프로젝트와 권한을 선택한다. 실제 Claude Code 2.1.289에서 격리 서버의 등록 URI `http://localhost:56382/callback`으로 로그인 완료와 MCP 명세 읽기를 확인했다. 반환 원문 hash도 대조했다. 프로젝트·명세 저장소는 합성 fixture이며 운영 전체 E2E 증거는 아니다.

## 스킬

`skills/timiroom` 폴더를 Codex의 사용자 `~/.agents/skills/timiroom` 또는 해당 프로젝트 `.agents/skills/timiroom`에 복사한다. Claude Code는 `~/.claude/skills/timiroom` 또는 프로젝트 `.claude/skills/timiroom`을 사용한다. 기존 동명의 스킬을 먼저 비교하고 병합한다. 계정 정보는 스킬에 넣지 않는다. 연결 후 `$timiroom` 또는 Claude의 `/timiroom`으로 명세 조회·변경안·검사 흐름을 참조한다.

## Slack

첫 버전은 서버에 설정한 워크스페이스 하나다. `slack-manifest.example.json`의 URL을 실제 API 호스트로 바꾸고 해당 테스트 워크스페이스에서 앱을 구성한다. Bot 권한은 commands, chat:write이며 대화 읽기 권한은 요청하지 않는다. 토큰 회전을 켜지 않은 앱의 환경 설정 방식이다. 여러 워크스페이스 설치 OAuth와 회전 토큰 관리가 필요한 경우 별도 설치 서비스가 필요하다.

```yaml
integration:
  slack:
    enabled: true
    team-id: ${SLACK_TEAM_ID}
    app-id: ${SLACK_APP_ID}
    bot-token: ${SLACK_BOT_TOKEN}
    signing-secret: ${SLACK_SIGNING_SECRET}
```

Bot을 테스트 채널에 초대한다. `/timiroom connect`의 일회용 코드를 티미룸 내 프로필의 Slack 연결에 입력한다. PM이 프로젝트와 채널 ID를 연결한다. 채널에서 `spec`, `review <proposalId> <revision>`, `pr <snapshotId> <repoId> <pullNumber> <headSha>`, `status <jobId>`를 요청할 수 있다. 명령의 즉시 응답과 조회 결과는 본인에게 표시한다. 검사 종류·완료 상태와 승인 링크는 설정된 프로젝트 채널로 전달한다. 원문·diff·검사 상세는 인증이 필요한 티미룸에서 확인한다.

계정 연결 해제는 해당 사용자가 설정한 채널도 해제한다. 전송 실패가 검사 결과를 바꾸지 않는다. 전송 결과가 불명확하면 자동 재전송하지 않는다. DB의 slack_command_request/slack_notification 상태와 안전한 error_code로 점검한다. request body·response_url·토큰·연결 코드를 로그에 남기지 않는다.

Slack 설치·실제 봇 송수신은 배포 전 검증 항목이다. Slack UI에서 사람이 게시한 채널 안내 메시지는 봇 전송이나 slash command callback 성공의 증거가 아니다. callback URL의 새 엔드포인트가 공개 환경에 배포되지 않았다면 명령 수신 E2E는 아직 확인할 수 없다. 기능 개발 승인과 develop 배포 승인은 구분한다.

공식 프로토콜 근거: [Slack 서명 검증](https://docs.slack.dev/authentication/verifying-requests-from-slack/), [Slack 명령](https://docs.slack.dev/interactivity/implementing-slash-commands/), [Codex MCP 설정](https://developers.openai.com/codex/mcp/).
