-- E9: LLM 계측. 호출마다 1행 — 비용·지연·캐시 효율을 관리자 대시보드에서 본다.
-- 토큰 수는 제공자 메타데이터가 없을 수 있어 nullable(스트리밍 등).
CREATE TABLE llm_call_log (
    id              BIGSERIAL PRIMARY KEY,
    call_type       VARCHAR(30) NOT NULL,     -- EXPLANATION | CHAT | TREND_SUMMARY | EMBEDDING
    model           VARCHAR(100),
    input_tokens    INT,
    output_tokens   INT,
    latency_ms      INT NOT NULL,
    cache_hit       BOOLEAN NOT NULL DEFAULT FALSE,
    error_message   TEXT,                     -- NULL = 성공
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_llm_call_log_created ON llm_call_log (created_at DESC);
