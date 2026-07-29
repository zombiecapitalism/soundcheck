-- NUMERIC(5,4)는 9.9999까지 허용하므로 확률·비율이 1을 넘어도 저장된다.
-- 예측 값은 화면에 그대로 노출되는 수치라 DB가 마지막 방어선이어야 한다.
ALTER TABLE prediction
    ADD CONSTRAINT ck_prediction_probability CHECK (probability >= 0 AND probability <= 1),
    ADD CONSTRAINT ck_prediction_encore_ratio CHECK (encore_ratio IS NULL OR (encore_ratio >= 0 AND encore_ratio <= 1)),
    ADD CONSTRAINT ck_prediction_played_within_sample CHECK (played_count >= 0 AND played_count <= sample_size);
