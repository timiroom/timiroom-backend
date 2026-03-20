-- pgvector 확장 활성화
CREATE EXTENSION IF NOT EXISTS vector;

-- 키워드 검색용 확장
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 문서 청크 저장 테이블
CREATE TABLE IF NOT EXISTS document_chunks (
                                               id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    content    TEXT        NOT NULL,
    metadata   JSONB       NOT NULL DEFAULT '{}',
    embedding  vector(3072),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

-- 벡터 검색용 HNSW 인덱스
CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw
    ON document_chunks
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 키워드 검색용 GIN 인덱스
CREATE INDEX IF NOT EXISTS idx_chunks_content_gin
    ON document_chunks
    USING gin (to_tsvector('english', content));