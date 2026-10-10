package com.timiroom.domain.integration.controller;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="integration.enabled",havingValue="true")
public class IntegrationLoginController {
    @GetMapping(value="/integrations/login",produces="text/html;charset=UTF-8")
    public ResponseEntity<String> login() {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(
            IntegrationPageView.start("티미룸 계정 연결", "login") + """
            <p>프로젝트를 만들 때 사용한 계정으로 로그인해 주세요. 로그인 후 연결할 프로젝트와 권한을 선택합니다.</p>
            <div class="providers">
              <a class="provider" href="/oauth2/authorization/google">Google 계정으로 계속</a>
              <a class="provider" href="/oauth2/authorization/github">GitHub 계정으로 계속</a>
            </div>
            <small>연결할 프로젝트와 허용할 기능은 다음 화면에서 직접 선택합니다.</small>
            """ + IntegrationPageView.end());
    }
}
