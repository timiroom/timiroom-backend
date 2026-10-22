package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/**
 * Slack 워크스페이스 여러 곳 지원. 워크스페이스마다 앱을 설치하면 봇 토큰(암호화)을 여기에 둔다.
 * 알림 큐에는 어느 워크스페이스로 보낼지 team_id를 함께 기록한다(예전 행은 비어 있으면 설정값 워크스페이스).
 */
public class V20261021_04__Slack_workspaces extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("CREATE TABLE slack_workspace ("
                + "team_id VARCHAR(64) PRIMARY KEY, team_name VARCHAR(255), bot_token_enc TEXT NOT NULL, bot_user_id VARCHAR(64), "
                + "installed_by BIGINT, installed_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked_at TIMESTAMP WITH TIME ZONE)");
            statement.execute("ALTER TABLE slack_notification ADD COLUMN team_id VARCHAR(64)");
        }
    }
}
