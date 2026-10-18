-- 명세 발행 지문(블록체인 기록). 발행 트랜잭션에서 PENDING으로 쌓이고, 체인 전송 워커가 기록한다.
-- 체인 관련 칸(chain_id ~ confirmed_at)은 전송 단계에서 채운다.
CREATE TABLE spec_anchor (
    anchor_id UUID PRIMARY KEY,
    snapshot_id UUID NOT NULL,
    project_id BIGINT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    manifest_json TEXT NOT NULL,
    digest VARCHAR(64) NOT NULL,
    prev_digest VARCHAR(64),
    state VARCHAR(32) NOT NULL CHECK (state IN ('PENDING','SENDING','SUBMITTED','CONFIRMED','FAILED','UNKNOWN')),
    backfilled BOOLEAN NOT NULL DEFAULT FALSE,
    chain_id BIGINT,
    contract_address VARCHAR(42),
    nonce BIGINT,
    tx_hash VARCHAR(66),
    raw_tx TEXT,
    block_number BIGINT,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_id UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_spec_anchor_snapshot UNIQUE (snapshot_id),
    CONSTRAINT uq_spec_anchor_revision UNIQUE (project_id, revision),
    CONSTRAINT fk_spec_anchor_snapshot FOREIGN KEY (snapshot_id) REFERENCES spec_snapshot(snapshot_id)
);
CREATE INDEX spec_anchor_queue_idx ON spec_anchor(state, next_attempt_at);
