package db.migration;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AddNotificationProjectIdMigrationTest {

    @Test
    void 컬럼을_추가하고_PIPELINE과_PROJECT_알림을_backfill한다() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:notification_project_id;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement s = connection.createStatement()) {
                s.execute("CREATE TABLE project (project_id BIGINT PRIMARY KEY)");
                s.execute("CREATE TABLE requirement (requirement_id BIGINT PRIMARY KEY, project_id BIGINT NOT NULL)");
                s.execute("CREATE TABLE pipeline_execution (execution_id BIGINT PRIMARY KEY, requirement_id BIGINT)");
                s.execute("""
                        CREATE TABLE notification (
                            notification_id BIGINT PRIMARY KEY, member_id BIGINT NOT NULL,
                            reference_type VARCHAR(20), reference_id BIGINT)""");
                s.execute("INSERT INTO project VALUES (5)");
                s.execute("INSERT INTO requirement VALUES (30, 5)");
                s.execute("INSERT INTO pipeline_execution VALUES (7, 30)");
                s.execute("INSERT INTO notification VALUES (1, 8, 'PIPELINE', 7)");      // -> 5
                s.execute("INSERT INTO notification VALUES (2, 8, 'PIPELINE', 999)");    // 실행 없음 -> NULL
                s.execute("INSERT INTO notification VALUES (3, 8, 'PULL_REQUEST', 2)");  // PR 번호 -> NULL
                s.execute("INSERT INTO notification VALUES (4, 8, 'PROJECT', 5)");       // -> 5
                s.execute("INSERT INTO notification VALUES (5, 8, 'PROJECT', 404)");     // 없는 프로젝트 -> NULL
            }

            V20261016_01__Add_notification_project_id.migrate(connection);
            // 재실행해도 안전해야 한다(컬럼 존재 시 ALTER 생략).
            assertDoesNotThrow(() -> V20261016_01__Add_notification_project_id.migrate(connection));

            assertThat(projectIdOf(connection, 1)).isEqualTo(5L);
            assertThat(projectIdOf(connection, 2)).isNull();
            assertThat(projectIdOf(connection, 3)).isNull();
            assertThat(projectIdOf(connection, 4)).isEqualTo(5L);
            assertThat(projectIdOf(connection, 5)).isNull();
        }
    }

    @Test
    void notification_테이블이_아직_없어도_신규_DB_부팅을_막지_않는다() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:empty_notification_project_id;MODE=PostgreSQL", "sa", "")) {
            assertDoesNotThrow(() -> V20261016_01__Add_notification_project_id.migrate(connection));
        }
    }

    private Long projectIdOf(Connection connection, long notificationId) throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT project_id FROM notification WHERE notification_id = " + notificationId)) {
            rs.next();
            long value = rs.getLong(1);
            return rs.wasNull() ? null : value;
        }
    }
}
