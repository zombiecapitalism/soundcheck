-- 재생목록(E12): 곡별 YouTube 영상 ID 캐시.
-- video_id NULL = 검색했지만 영상을 못 찾음 — 재검색으로 쿼터를 태우지 않기 위한 네거티브 캐시.
CREATE TABLE song_video (
    id           BIGSERIAL PRIMARY KEY,
    artist_mbid  UUID NOT NULL REFERENCES artist(mbid),
    song_key     VARCHAR(300) NOT NULL,
    video_id     VARCHAR(20),
    video_title  VARCHAR(300),
    searched_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (artist_mbid, song_key)
);
