package com.rag.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 애플리케이션 컨텍스트가 뜨는지만 확인한다.
 *
 * FOUNDRY_API_KEY는 application.yml에 기본값이 없다. 일부러 그렇게 두었다 —
 * 키 없이 뜬 서버는 첫 요청에서야 실패하므로, 없으면 부팅 단계에서 바로 알아야 한다.
 * 대신 그 값이 없는 환경(CI)에서는 이 테스트가 자리표시자를 못 채워 깨진다.
 * 그래서 여기서만 가짜 값을 넣는다. 컨텍스트 로딩에는 값의 내용이 쓰이지 않는다.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "FOUNDRY_API_KEY=test-key-not-used",
})
class RagPipelineApplicationTests {

    @Test
    void contextLoads() {
    }

}
