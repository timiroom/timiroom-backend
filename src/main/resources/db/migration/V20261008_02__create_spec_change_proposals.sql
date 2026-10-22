CREATE TABLE spec_change_proposal (
    proposal_id UUID PRIMARY KEY,
    project_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    snapshot_id UUID NOT NULL REFERENCES spec_snapshot(snapshot_id),
    base_json TEXT NOT NULL,
    input_json TEXT NOT NULL,
    instruction TEXT NOT NULL,
    state VARCHAR(32) NOT NULL,
    proposal_revision INTEGER NOT NULL CHECK (proposal_revision > 0),
    documents_json TEXT,
    diffs_json TEXT,
    impact_json TEXT,
    executor VARCHAR(255),
    result_hash VARCHAR(64),
    approved_snapshot_id UUID REFERENCES spec_snapshot(snapshot_id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX spec_change_proposal_project_idx ON spec_change_proposal(project_id, created_at);
