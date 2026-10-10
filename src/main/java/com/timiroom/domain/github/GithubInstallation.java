package com.timiroom.domain.github;

import com.timiroom.global.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * GitHub App 설치 단위.
 * 한 조직(또는 개인 계정)에 App이 설치되면 installation_id가 발급되고,
 * 이 값으로 installation access token을 받아 해당 계정의 레포에 접근한다.
 */
@Entity
@Table(name = "github_installation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class GithubInstallation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "github_installation_id")
    private Long id;

    @Column(name = "installation_id", nullable = false, unique = true)
    private Long installationId;

    @Column(name = "account_login", length = 100, nullable = false)
    private String accountLogin;

    @Column(name = "account_type", length = 20)
    private String accountType; // Organization | User

    @Column(name = "team_id")
    private Long teamId; // 연결된 워크스페이스 (미연결이면 null)

    public void updateAccount(String accountLogin, String accountType) {
        this.accountLogin = accountLogin;
        this.accountType = accountType;
    }

    public void linkTeam(Long teamId) {
        this.teamId = teamId;
    }
}
