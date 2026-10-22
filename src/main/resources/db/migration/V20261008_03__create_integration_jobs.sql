CREATE TABLE integration_job (
    job_id UUID PRIMARY KEY,
    project_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    request_json TEXT NOT NULL,
    binding_key VARCHAR(255) NOT NULL,
    result_json TEXT,
    error_code VARCHAR(255),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 2),
    lease_id UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_integration_job_request UNIQUE (project_id,actor_id,kind,idempotency_key)
);
CREATE INDEX integration_job_queue_idx ON integration_job(state, created_at);
CREATE INDEX integration_job_binding_idx ON integration_job(binding_key,kind,state);
