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

-- 벡터 검색용 인덱스
-- pgvector HNSW/IVFFlat 인덱스는 최대 2000 차원 제한
-- 3072 차원(text-embedding-3-large)은 순차 스캔 사용
-- 데이터 많아지면 차원 축소 or pgvector 0.8+ 업그레이드 필요

-- 키워드 검색용 GIN 인덱스
CREATE INDEX IF NOT EXISTS idx_chunks_content_gin
    ON document_chunks
    USING gin (to_tsvector('english', content));