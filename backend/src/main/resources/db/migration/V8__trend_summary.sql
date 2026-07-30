-- E4(요약부): 예측 변화 LLM 요약 캐시.
-- 예측 재계산 시에만 갱신한다 — 조회당 LLM 호출 없음. 변화 곡이 없으면 NULL(화면 미표시).
ALTER TABLE target_event
    ADD COLUMN trend_summary    TEXT,
    ADD COLUMN trend_summary_at TIMESTAMPTZ;
