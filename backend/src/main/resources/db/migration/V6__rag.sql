-- RAG 저장소 (설계 문서 2.4). 임베딩은 OpenAI text-embedding-3-small(1536차원).
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE rag_document (
    id              BIGSERIAL PRIMARY KEY,
    artist_mbid     UUID NOT NULL REFERENCES artist(mbid),
    song_key        VARCHAR(300),                    -- 곡 단위 문서면 채움
    doc_type        VARCHAR(30) NOT NULL,            -- SONG | ALBUM | ARTIST
    title           VARCHAR(300) NOT NULL,
    source_name     VARCHAR(200) NOT NULL,
    source_url      TEXT NOT NULL,                   -- 출처 표기 필수
    collected_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 같은 아티스트의 같은 출처 문서는 한 번만 — 재수집 시 스킵 판정 기준
    UNIQUE (artist_mbid, source_url)
);

-- 검색 필터(아티스트 + 곡 메타)용
CREATE INDEX idx_rag_document_filter ON rag_document (artist_mbid, song_key);

CREATE TABLE rag_chunk (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT NOT NULL REFERENCES rag_document(id) ON DELETE CASCADE,
    chunk_index     SMALLINT NOT NULL,
    content         TEXT NOT NULL,
    embedding       vector(1536) NOT NULL,
    token_count     SMALLINT,
    UNIQUE (document_id, chunk_index)
);

CREATE INDEX idx_rag_chunk_embedding ON rag_chunk
    USING hnsw (embedding vector_cosine_ops);
