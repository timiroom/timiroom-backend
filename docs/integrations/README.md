# AI 도구와 Slack 연결

OAuth·MCP·명세 변경안·Slack 기본 기능은 develop에 병합된 구현이다. 서비스 실행과 실제 외부 클라이언트 연결은 각각 검증한다. 서버 앞 네트워크가 외부 HTTPS 요청을 차단하면 공개 HTTP 주소만으로 Slack 명령을 받을 수 없다.

## 서버 설정

### 웹에서 Slack 계정 연결

마이페이지의 Slack `연결` 버튼은 서버에서 일회성 state와 nonce를 만든 뒤 Slack OpenID 승인 화면을 연다. 승인 후 현재 로그인한 티미룸 계정에 Slack 사용자 ID를 연결한다. 봇 설치·Socket Mode 설정은 기존 것을 사용하며, 개인 계정 연결에서 봇 권한을 다시 요청하지 않는다. 기존 `/timiroom connect` 코드 API도 유지한다.

추가 설정은 `INTEGRATION_SLACK_CLIENT_ID`, `INTEGRATION_SLACK_CLIENT_SECRET`이다. Slack 앱 Basic Information의 OAuth Client ID와 Client Secret이며 bot token/signing secret과 다른 값이다. GitHub에서는 repository variable `SLACK_CLIENT_ID`와 production environment secret `APP_SLACK_CLIENT_SECRET`로 제공한다. 두 값이 없으면 기존 Slack 기능은 유지되고 웹 연결 시작은 503으로 명확히 실패한다.

Slack OAuth & Permissions에 `https://api.timiroom.kro.kr/integrations/slack/oauth/callback`을 Redirect URL로 등록하고 Sign in with Slack의 `openid` 범위를 사용할 수 있게 설정한다. 개발 서버는 해당 환경의 integration.issuer에 같은 경로를 붙인다. 이메일·프로필·추가 봇 권한은 요청하지 않는다.

Slack의 form_post 응답은 쿠키를 생성하지 않는 공개 POST relay를 거쳐 동일 API origin의 인증된 GET으로 돌아온다. 여기서 원래 세션, 티미룸 회원, 5분 유효 state, nonce, 서명·issuer·audience·만료와 허용된 Slack team을 검증한다. relay는 계정을 연결하지 않으며 no-store/no-referrer를 적용한다. 프록시 접근 로그에서도 callback/complete의 query string을 기록하지 않는다. 기존 계정 연결을 조용히 교체하지 않으며 실패·취소 시 알림 설정을 바꾸지 않는다.

운영 확인: 미연결 버튼 → Slack 승인 → 복귀 후 서버 `connected=true` 확인, 취소/만료/다른 workspace 거절, 연결 해제 후 `연결` 재표시를 확인한다. 로컬·CI 테스트 통과와 실제 Slack 승인은 별도 검증이다.

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
    socket-mode:
      enabled: false
    app-token: ${SLACK_APP_TOKEN:}
```

Bot을 테스트 채널에 초대한다. `/timiroom connect`의 일회용 코드를 티미룸 내 프로필의 Slack 연결에 입력한다. PM이 프로젝트와 채널 ID를 연결한다. 채널에서 `spec`, `review <proposalId> <revision>`, `pr <snapshotId> <repoId> <pullNumber> <headSha>`, `status <jobId>`를 요청할 수 있다. 명령의 즉시 응답과 조회 결과는 본인에게 표시한다. 검사 종류·완료 상태와 승인 링크는 설정된 프로젝트 채널로 전달한다. 원문·diff·검사 상세는 인증이 필요한 티미룸에서 확인한다.

계정 연결 해제는 해당 사용자가 설정한 채널도 해제한다. 전송 실패가 검사 결과를 바꾸지 않는다. 전송 결과가 불명확하면 자동 재전송하지 않는다. DB의 slack_command_request/slack_notification 상태와 안전한 error_code로 점검한다. request body·response_url·토큰·연결 코드를 로그에 남기지 않는다.

### 외부 인바운드가 제한된 서버

`socket-mode.enabled=true`와 `app-token`으로 Socket Mode를 선택할 수 있다. Slack 앱의 기존 `connections:write` app-level token을 사용하고 앱 설정에서도 Socket Mode를 켠다. 서버는 Slack으로 HTTPS/WSS 443 연결을 열며 별도 인바운드 포트가 필요하지 않다. Bot 권한은 여전히 commands/chat:write다. 워크스페이스·사용자·프로젝트 권한 검사는 기존 서비스와 공유한다. `hello`의 앱 ID와 명령의 workspace를 검증한 뒤 명령을 처리한다.

SDK가 ping·재접속·주기적인 URL 교체를 관리한다. 최초 연결 실패도 백엔드 기동을 막지 않고 10초 후 재시도한다. HTTP 서명 검증은 유지해 Socket Mode를 껐을 때 기존 callback으로 복귀할 수 있다. 명령은 즉시 ACK한 후 제한된 작업 풀에서 처리하고 결과를 요청자에게만 보낸다. envelope ID는 PostgreSQL 중복 방지 키로 사용하므로 replica가 달라도 같은 검사를 중복 접수하지 않는다. ACK 직후 프로세스가 종료되거나 전송이 실패하면 해당 사용자가 명령을 다시 실행해야 한다. 불명확한 전송을 자동 반복하지 않는다.

운영 로그는 `SLACK_SOCKET_CONNECTED`, `SLACK_SOCKET_DISCONNECTED`, `SLACK_SOCKET_CONNECT_FAILED` 등 코드만 기록한다. SDK의 raw payload와 인증 URL 로그는 사용하지 않는다. Slack 연결 장애와 제품 API readiness는 구분한다. Socket Mode는 현재 단일 워크스페이스 운영용이며 공개 Slack Marketplace 배포를 지원하는 방식으로 표시하지 않는다.

설치·실제 봇 송수신·사용자 명령 수신은 각각 검증한다. Socket Mode를 켜기 전에 새 백엔드 이미지·설정을 준비하고, 켠 뒤 실제 `/timiroom help`, 계정 연결, 프로젝트 조회·검사를 확인한다. mode를 HTTP로 되돌릴 때는 외부 callback의 443 도달을 먼저 확인한다.

GitHub 배포 설정은 backend repository variables `INTEGRATION_ENABLED`, `INTEGRATION_ISSUER`, `INTEGRATION_SLACK_ENABLED`, `SLACK_TEAM_ID`, `SLACK_APP_ID`와 production environment secrets `APP_INTEGRATION_CURSOR_SECRET`, `APP_SLACK_BOT_TOKEN`, `APP_SLACK_SIGNING_SECRET`를 사용한다. workflow가 기존 backend-secrets와 함께 봉인해 운영에 전달한다. 기능 플래그 기본값은 false이며 활성화에 필요한 값이 없으면 배포 설정 생성이 실패한다. 프론트의 두 build variable은 backend 배포 설정과 함께 확인한다.

Socket Mode 추가 설정은 repository variable `SLACK_SOCKET_MODE_ENABLED=true`와 production environment secret `APP_SLACK_APP_TOKEN`이다. 기본값은 false이며 true일 때 app token이 없으면 배포를 중단한다. 토큰은 Git·스킬·Obsidian에 저장하지 않는다. [Slack 공식 Socket Mode 문서](https://docs.slack.dev/apis/events-api/using-socket-mode/).

초기 배포 순서는 consistency → backend → frontend다. 각 PR CI를 통과한 SHA를 병합하고, 해당 이미지가 Ready이며 공개 인증 경계가 정상인지 확인한 뒤 다음 서비스를 진행한다. pipeline 회귀 수정은 자체 CI와 이미지 검증 후 별도로 병합한다. 장애 시 이전 이미지로 되돌리고 기능 플래그를 false로 재배포한다. 추가 migration은 즉시 역삭제하지 않는다. 이미 발행한 snapshot·승인 이력을 보존하고, 데이터 호환성을 확인한 뒤 rollback 범위를 결정한다.

공식 프로토콜 근거: [Slack 서명 검증](https://docs.slack.dev/authentication/verifying-requests-from-slack/), [Slack 명령](https://docs.slack.dev/interactivity/implementing-slash-commands/), [Codex MCP 설정](https://developers.openai.com/codex/mcp/).
