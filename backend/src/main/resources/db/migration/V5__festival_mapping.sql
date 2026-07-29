-- show_type 판정용 수동 매핑 (docs/setlist-schema.md 4장).
-- venue/tour명에 "festival" 키워드가 없는 페스티벌 공연장(예: 삼락생태공원)을
-- 운영자가 직접 등록한다. venue명 또는 tour명에 keyword가 포함되면 FESTIVAL로 판정.
CREATE TABLE festival_mapping (
    id      BIGSERIAL PRIMARY KEY,
    keyword VARCHAR(300) NOT NULL,
    CONSTRAINT uq_festival_mapping_keyword UNIQUE (keyword)
);
