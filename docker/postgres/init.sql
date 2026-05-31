-- pgvector 확장 (rag-pipeline 벡터 검색용)
CREATE EXTENSION IF NOT EXISTS vector;

-- 키워드 검색용 확장
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- RAG 문서 청크 저장 테이블 (rag-pipeline KafkaConsumerService가 사용)
CREATE TABLE IF NOT EXISTS document_chunks (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    content    TEXT        NOT NULL,
    metadata   JSONB       NOT NULL DEFAULT '{}',
    embedding  vector(3072),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 키워드 검색용 GIN 인덱스
CREATE INDEX IF NOT EXISTS idx_chunks_content_gin
    ON document_chunks
    USING gin (to_tsvector('english', content));
