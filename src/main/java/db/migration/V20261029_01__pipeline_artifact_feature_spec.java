package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V20261029_01__pipeline_artifact_feature_spec extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        // Hibernate created this enum check before FEATURE_SPEC existed and never updates it,
        // so completed pipelines failed to save their feature spec artifact.
        // Fresh schemas (including H2 tests) receive the new check from Hibernate.
        if (!connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) return;
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    DO $$
                    BEGIN
                        IF to_regclass('pipeline_artifact') IS NOT NULL THEN
                            ALTER TABLE pipeline_artifact DROP CONSTRAINT IF EXISTS pipeline_artifact_artifact_type_check;
                            ALTER TABLE pipeline_artifact ADD CONSTRAINT pipeline_artifact_artifact_type_check
                                CHECK (artifact_type IN ('PRD', 'DB_SCHEMA', 'API_SPEC', 'FEATURE_LIST', 'FEATURE_SPEC',
                                                         'MARKET_RESEARCH', 'QA_REPORT'));
                        END IF;
                    END $$;
                    """);
        }
    }
}
