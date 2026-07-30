-- 곡 배경 설명(RAG 생성 결과) 캐시.
-- 같은 곡을 볼 때마다 LLM을 다시 부르지 않기 위한 것으로, 원천은 항상 rag_chunk 검색 + 생성이다.
-- 새 RAG 문서가 수집되면 해당 아티스트의 캐시를 지워 낡은 설명이 남지 않게 한다.
CREATE TABLE song_explanation (
    id              BIGSERIAL PRIMARY KEY,
    artist_mbid     UUID NOT NULL REFERENCES artist(mbid),
    song_key        VARCHAR(300) NOT NULL,
    content         TEXT NOT NULL,
    sources         JSONB NOT NULL,                  -- [{name, url, title}] — 출처 없는 설명은 없다
    generated_at    TIMESTAMPTZ NOT NULL,
    UNIQUE (artist_mbid, song_key)
);
