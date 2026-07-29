-- 카운트 세 컬럼은 @Embedded(CollectionCounts)로 묶여 있다.
-- 임베디드에 매핑된 컬럼이 전부 NULL이면 Hibernate는 임베디드 객체 자체를 null로 돌려주므로,
-- 엔티티를 거치지 않고 들어온 행 하나 때문에 카운트를 읽는 쪽에서 NPE가 난다.
-- DEFAULT 0이라는 원래 의도대로 NULL을 아예 막는다.
UPDATE collection_log SET fetched_count = 0 WHERE fetched_count IS NULL;
UPDATE collection_log SET updated_count = 0 WHERE updated_count IS NULL;
UPDATE collection_log SET skipped_count = 0 WHERE skipped_count IS NULL;

ALTER TABLE collection_log
    ALTER COLUMN fetched_count SET NOT NULL,
    ALTER COLUMN updated_count SET NOT NULL,
    ALTER COLUMN skipped_count SET NOT NULL;
