CREATE TABLE spec_snapshot (
    snapshot_id UUID PRIMARY KEY,
    project_id BIGINT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    published_by BIGINT NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL,
    documents_json TEXT NOT NULL,
    CONSTRAINT uq_spec_snapshot_revision UNIQUE (project_id, revision)
);
