-- pgvector 확장 활성화
CREATE EXTENSION IF NOT EXISTS vector;

-- 키워드 검색용 확장
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 문서 청크 저장 테이블 (1024차원 — text-embedding-3-large, dimensions: 1024)
CREATE TABLE IF NOT EXISTS document_chunks (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    content      TEXT        NOT NULL,
    metadata     JSONB       NOT NULL DEFAULT '{}',
    embedding    vector(1024),
    tokens       TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    content_hash TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_chunks_content_hash UNIQUE (content_hash)
);

-- 벡터 검색용 IVF_FLAT 인덱스 (pgvector, cosine distance)
CREATE INDEX IF NOT EXISTS idx_chunks_embedding
    ON document_chunks
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- 키워드 검색용 GIN 인덱스
CREATE INDEX IF NOT EXISTS idx_chunks_tokens
    ON document_chunks
    USING gin (tokens);

-- content_hash 중복 방지 인덱스
CREATE UNIQUE INDEX IF NOT EXISTS idx_chunks_content_hash
    ON document_chunks (content_hash)
    WHERE content_hash IS NOT NULL;

-- 한국어 ILIKE 검색용 trigram 인덱스
CREATE INDEX IF NOT EXISTS idx_chunks_content_trgm
    ON document_chunks
    USING gin (content gin_trgm_ops);

-- ── RL 테이블 ────────────────────────────────────────────────────

-- 검색마다 사용된 파라미터와 결과를 기록 (피드백 매핑용)
CREATE TABLE IF NOT EXISTS rl_search_log (
    pipeline_id          TEXT             PRIMARY KEY,
    vector_weight        DOUBLE PRECISION NOT NULL,
    keyword_weight       DOUBLE PRECISION NOT NULL,
    similarity_threshold DOUBLE PRECISION NOT NULL,
    chunk_count          INT,
    avg_cohere_score     DOUBLE PRECISION,
    created_at           TIMESTAMPTZ      DEFAULT NOW()
);

-- RL이 학습한 현재 최적 파라미터 (행은 항상 id=1 하나)
CREATE TABLE IF NOT EXISTS rl_params (
    id                   INT         PRIMARY KEY,
    vector_weight        DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    keyword_weight       DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    similarity_threshold DOUBLE PRECISION NOT NULL DEFAULT 0.3,
    total_runs           INT         NOT NULL DEFAULT 0,
    total_accepted       INT         NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ DEFAULT NOW()
);

INSERT INTO rl_params (id) VALUES (1) ON CONFLICT DO NOTHING;

-- ── Agent RL 테이블 ──────────────────────────────────────────────

-- 파이프라인마다 에이전트별 사용된 temperature 기록
CREATE TABLE IF NOT EXISTS agent_rl_log (
    pipeline_id   TEXT             NOT NULL,
    agent_name    TEXT             NOT NULL,
    temperature   DOUBLE PRECISION NOT NULL,
    quality_score DOUBLE PRECISION,
    created_at    TIMESTAMPTZ      DEFAULT NOW(),
    PRIMARY KEY (pipeline_id, agent_name)
);

-- 에이전트별 현재 최적 temperature
CREATE TABLE IF NOT EXISTS agent_rl_params (
    agent_name   TEXT             PRIMARY KEY,
    temperature  DOUBLE PRECISION NOT NULL DEFAULT 0.1,
    total_runs   INT              NOT NULL DEFAULT 0,
    total_score  DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    updated_at   TIMESTAMPTZ      DEFAULT NOW()
);

INSERT INTO agent_rl_params (agent_name) VALUES ('dba') ON CONFLICT DO NOTHING;
INSERT INTO agent_rl_params (agent_name) VALUES ('api') ON CONFLICT DO NOTHING;