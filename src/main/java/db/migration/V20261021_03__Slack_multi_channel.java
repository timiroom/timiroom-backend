package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * 프로젝트 하나에 Slack 채널을 여러 개 연결하고, 채널마다 받을 알림 종류(events)를 고를 수 있게 한다.
 * 채널 하나는 여전히 프로젝트 하나에만 연결된다(UNIQUE team_id, channel_id 유지).
 * events: CHANGE(변경안 승인 요청), REVIEW(교차검증 결과), PR(PR 검사 결과)를 쉼표로 이은 값.
 */
public class V20261021_03__Slack_multi_channel extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean postgres = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        try (Statement statement = connection.createStatement()) {
            if (postgres) {
                statement.execute("ALTER TABLE slack_project_channel DROP CONSTRAINT IF EXISTS slack_project_channel_pkey");
            } else {
                statement.execute("ALTER TABLE slack_project_channel DROP PRIMARY KEY");
            }
            statement.execute("ALTER TABLE slack_project_channel ADD PRIMARY KEY (project_id, channel_id)");
            statement.execute("ALTER TABLE slack_project_channel ADD COLUMN events VARCHAR(100) DEFAULT 'CHANGE,REVIEW,PR' NOT NULL");
        }
    }
}
