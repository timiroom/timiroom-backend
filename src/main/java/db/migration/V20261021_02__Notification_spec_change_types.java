package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 변경안 검토 대기·교차검증 완료 알림 종류를 추가한다.
 * notification 표는 Hibernate가 만든 enum CHECK 제약을 갖고 있어 새 값을 넣으려면 제약을 다시 만들어야 한다.
 */
public class V20261021_02__Notification_spec_change_types extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String schema = connection.getSchema();
        boolean exists = false;
        for (String name : new String[]{"notification", "NOTIFICATION"}) {
            try (ResultSet tables = connection.getMetaData().getTables(null, schema, name, new String[]{"TABLE"})) {
                exists |= tables.next();
            }
        }
        if (!exists) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE notification DROP CONSTRAINT IF EXISTS notification_type_check");
            statement.execute("ALTER TABLE notification ADD CONSTRAINT notification_type_check CHECK (type IN "
                    + "('PIPELINE_COMPLETE', 'PIPELINE_FAILED', 'TEAM_INVITE', 'MENTION', 'PR_CONSISTENCY_REVIEW', "
                    + "'SPEC_CHANGE_READY', 'SPEC_REVIEW_DONE'))");
        }
    }
}
