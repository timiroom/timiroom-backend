package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 기존 notification 테이블의 Hibernate enum CHECK 제약을 최신 enum 값으로 확장한다.
 *
 * <p>Hibernate ddl-auto=update는 이미 만들어진 enum CHECK 제약을 갱신하지 않으므로,
 * PR 정합성 알림 저장 시 PULL_REQUEST / PR_CONSISTENCY_REVIEW 값이 거부될 수 있다.</p>
 */
public class V20260731_01__Expand_notification_enum_constraints extends BaseJavaMigration {

    private static final String TABLE_NAME = "notification";

    @Override
    public void migrate(Context context) throws Exception {
        migrate(context.getConnection());
    }

    static void migrate(Connection connection) throws SQLException {
        if (!tableExists(connection, TABLE_NAME)) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE notification "
                    + "DROP CONSTRAINT IF EXISTS notification_reference_type_check");
            statement.execute("ALTER TABLE notification "
                    + "ADD CONSTRAINT notification_reference_type_check "
                    + "CHECK (reference_type IS NULL OR reference_type IN "
                    + "('PIPELINE', 'PROJECT', 'TEAM', 'PULL_REQUEST'))");

            statement.execute("ALTER TABLE notification "
                    + "DROP CONSTRAINT IF EXISTS notification_type_check");
            statement.execute("ALTER TABLE notification "
                    + "ADD CONSTRAINT notification_type_check "
                    + "CHECK (type IN "
                    + "('PIPELINE_COMPLETE', 'PIPELINE_FAILED', 'TEAM_INVITE', 'MENTION', "
                    + "'PR_CONSISTENCY_REVIEW'))");
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Set<String> schemas = new LinkedHashSet<>();
        if (connection.getSchema() != null && !connection.getSchema().isBlank()) {
            schemas.add(connection.getSchema());
        }
        schemas.add(null);

        Set<String> names = new LinkedHashSet<>();
        names.add(tableName);
        names.add(tableName.toLowerCase(Locale.ROOT));
        names.add(tableName.toUpperCase(Locale.ROOT));
        for (String schema : schemas) {
            for (String name : names) {
                try (ResultSet tables = metadata.getTables(null, schema, name, new String[]{"TABLE"})) {
                    if (tables.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
