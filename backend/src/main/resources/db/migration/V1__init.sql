-- 아티스트 (setlist.fm/MusicBrainz MBID 기준)
CREATE TABLE artist (
    mbid            UUID PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    sort_name       VARCHAR(200),
    setlist_fm_url  TEXT,
    is_target       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 공연 (setlist.fm의 setlist 1건 = 공연 1회)
CREATE TABLE show (
    setlist_id      VARCHAR(20) PRIMARY KEY,
    version_id      VARCHAR(20) NOT NULL,
    artist_mbid     UUID NOT NULL REFERENCES artist(mbid),
    event_date      DATE NOT NULL,
    tour_name       VARCHAR(200),
    venue_name      VARCHAR(300),
    city_name       VARCHAR(200),
    country_code    CHAR(2),
    show_type       VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    song_count      SMALLINT NOT NULL DEFAULT 0,
    source_url      TEXT,
    raw_json        JSONB NOT NULL,
    collected_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_show_artist_date ON show (artist_mbid, event_date DESC);
CREATE INDEX idx_show_type ON show (artist_mbid, show_type, event_date DESC);

-- 공연별 연주곡 (순서 포함)
CREATE TABLE show_song (
    id              BIGSERIAL PRIMARY KEY,
    setlist_id      VARCHAR(20) NOT NULL REFERENCES show(setlist_id) ON DELETE CASCADE,
    set_index       SMALLINT NOT NULL,
    is_encore       BOOLEAN NOT NULL DEFAULT FALSE,
    position_in_set SMALLINT NOT NULL,
    position_total  SMALLINT NOT NULL,
    song_name       VARCHAR(300) NOT NULL,
    song_key        VARCHAR(300) NOT NULL,
    is_cover        BOOLEAN NOT NULL DEFAULT FALSE,
    cover_artist    VARCHAR(200),
    is_tape         BOOLEAN NOT NULL DEFAULT FALSE,
    note            TEXT
);

CREATE INDEX idx_show_song_key ON show_song (song_key);
CREATE UNIQUE INDEX uq_show_song ON show_song (setlist_id, position_total);

-- 예측 대상 이벤트 (예: 2026 부산국제록페스티벌 - Megadeth)
CREATE TABLE target_event (
    id                  BIGSERIAL PRIMARY KEY,
    artist_mbid         UUID NOT NULL REFERENCES artist(mbid),
    event_name          VARCHAR(200) NOT NULL,
    event_date          DATE NOT NULL,
    venue_name          VARCHAR(300),
    expected_show_type  VARCHAR(20) NOT NULL,
    expected_song_count SMALLINT,
    actual_setlist_id   VARCHAR(20) REFERENCES show(setlist_id),
    UNIQUE (artist_mbid, event_date)
);

-- 곡별 예측 결과
CREATE TABLE prediction (
    id                  BIGSERIAL PRIMARY KEY,
    target_event_id     BIGINT NOT NULL REFERENCES target_event(id) ON DELETE CASCADE,
    song_key            VARCHAR(300) NOT NULL,
    song_name           VARCHAR(300) NOT NULL,
    probability         NUMERIC(5,4) NOT NULL,
    rank                SMALLINT NOT NULL,
    played_count        SMALLINT NOT NULL,
    sample_size         SMALLINT NOT NULL,
    avg_position        NUMERIC(4,1),
    encore_ratio        NUMERIC(5,4),
    evidence            JSONB,
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (target_event_id, song_key)
);

CREATE INDEX idx_prediction_rank ON prediction (target_event_id, rank);

-- 배치 이력
CREATE TABLE collection_log (
    id              BIGSERIAL PRIMARY KEY,
    artist_mbid     UUID,
    job_type        VARCHAR(30) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    fetched_count   INT DEFAULT 0,
    updated_count   INT DEFAULT 0,
    skipped_count   INT DEFAULT 0,
    error_message   TEXT,
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ
);
