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
 * notification.project_id (nullable) 를 추가하고 기존 알림을 가능한 범위에서 채운다.
 *
 * <p>notification 테이블은 Hibernate ddl-auto 가 만들기 때문에 새 DB 에서는 Flyway 시점에 아직 없다.
 * 그 경우 아무 것도 하지 않고, 컬럼은 이후 Hibernate 가 엔티티 기준으로 만든다.</p>
 *
 * <ul>
 *   <li>PIPELINE: reference_id = pipeline_execution.execution_id -> requirement.project_id</li>
 *   <li>PROJECT: reference_id 자체가 project_id</li>
 *   <li>PULL_REQUEST: reference_id 는 저장소 정보 없는 PR 번호뿐이라 역추적할 수 없어 NULL 로 둔다.</li>
 * </ul>
 */
public class V20261016_01__Add_notification_project_id extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        migrate(context.getConnection());
    }

    static void migrate(Connection connection) throws SQLException {
        if (!tableExists(connection, "notification")) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            if (!columnExists(connection, "notification", "project_id")) {
                statement.execute("ALTER TABLE notification ADD COLUMN project_id BIGINT");
            }

            if (tableExists(connection, "pipeline_execution") && tableExists(connection, "requirement")) {
                statement.executeUpdate("UPDATE notification SET project_id = ("
                        + "SELECT r.project_id FROM pipeline_execution e "
                        + "JOIN requirement r ON r.requirement_id = e.requirement_id "
                        + "WHERE e.execution_id = notification.reference_id) "
                        + "WHERE project_id IS NULL AND reference_type = 'PIPELINE'");
            }

            if (tableExists(connection, "project")) {
                statement.executeUpdate("UPDATE notification SET project_id = reference_id "
                        + "WHERE project_id IS NULL AND reference_type = 'PROJECT' "
                        + "AND reference_id IN (SELECT p.project_id FROM project p)");
            }
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String schema = currentSchema(connection);
        for (String name : caseVariants(tableName)) {
            try (ResultSet tables = metadata.getTables(null, schema, name, new String[]{"TABLE"})) {
                if (tables.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String schema = currentSchema(connection);
        for (String table : caseVariants(tableName)) {
            for (String column : caseVariants(columnName)) {
                try (ResultSet columns = metadata.getColumns(null, schema, table, column)) {
                    if (columns.next()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // A table in an unrelated schema is not the unqualified table altered above.
    private static String currentSchema(Connection connection) throws SQLException {
        String schema = connection.getSchema();
        return schema != null && !schema.isBlank() ? schema : null;
    }

    private static Set<String> caseVariants(String name) {
        Set<String> names = new LinkedHashSet<>();
        names.add(name);
        names.add(name.toLowerCase(Locale.ROOT));
        names.add(name.toUpperCase(Locale.ROOT));
        return names;
    }
}
