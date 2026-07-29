-- 재적재 시 곡 목록을 통째로 교체하면 같은 (setlist_id, position_total)을 다시 채우게 된다.
-- Hibernate는 한 번의 flush에서 자식 INSERT를 orphan DELETE보다 먼저 실행하므로,
-- 문장 단위로 검사하면 아직 지워지지 않은 기존 행과 충돌한다.
-- 검사 시점을 커밋으로 미뤄 교체가 한 트랜잭션 안에서 끝나도록 한다.
-- (UNIQUE INDEX는 DEFERRABLE로 만들 수 없어 테이블 제약으로 바꾼다.)
DROP INDEX uq_show_song;

ALTER TABLE show_song
    ADD CONSTRAINT uq_show_song UNIQUE (setlist_id, position_total) DEFERRABLE INITIALLY DEFERRED;
