package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V20261023_02__pipeline_execution_storage_pending extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        // Existing PostgreSQL databases have Hibernate's old enum check.
        // Fresh schemas (including H2 tests) receive the new check from Hibernate.
        if (!connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) return;
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    DO $$
                    BEGIN
                        IF to_regclass('pipeline_execution') IS NOT NULL THEN
                            ALTER TABLE pipeline_execution DROP CONSTRAINT IF EXISTS pipeline_execution_status_check;
                            ALTER TABLE pipeline_execution ADD CONSTRAINT pipeline_execution_status_check
                                CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'RETRYING', 'STORAGE_PENDING'));
                            IF NOT EXISTS (SELECT 1 FROM pg_constraint
                                WHERE conrelid='pipeline_result_receipt'::regclass AND conname='fk_pipeline_receipt_execution') THEN
                                ALTER TABLE pipeline_result_receipt ADD CONSTRAINT fk_pipeline_receipt_execution
                                    FOREIGN KEY(pipeline_id) REFERENCES pipeline_execution(pipeline_id) ON DELETE CASCADE;
                            END IF;
                        END IF;
                    END $$;
                    """);
        }
    }
}
