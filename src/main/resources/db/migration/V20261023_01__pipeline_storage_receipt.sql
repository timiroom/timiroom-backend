CREATE TABLE IF NOT EXISTS pipeline_result_receipt (
    pipeline_id VARCHAR(36) PRIMARY KEY,
    payload TEXT NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_pipeline_receipt_pending
    ON pipeline_result_receipt(completed, created_at);
