# 데이터 설계 — setlist.fm 연동 & DB 스키마 v0.1

작성일: 2026-07-29 / 프로젝트: 셋리스트 예측 & 예습 서비스

---

## 1. API 연동 기본

### 1.1 키 발급

- 신청: `https://www.setlist.fm/settings/api` (setlist.fm 로그인 필요, 가입 무료)
- 인증 헤더: `x-api-key: {발급키}`
- JSON 응답을 받으려면 `Accept: application/json` 헤더 필수 (기본값은 XML)
- **비상업 프로젝트만 무료** — 포트폴리오/개인 프로젝트는 해당. 서비스에 setlist.fm 출처 표기 권장

### 1.2 사용할 엔드포인트

| 용도 | 엔드포인트 |
|------|-----------|
| 아티스트 검색 (MBID 획득) | `GET /rest/1.0/search/artists?artistName={name}` |
| 아티스트별 셋리스트 목록 | `GET /rest/1.0/artist/{mbid}/setlists?p={page}` |
| 단건 셋리스트 조회 | `GET /rest/1.0/setlist/{setlistId}` |

- Base URL: `https://api.setlist.fm/rest`
- 아티스트 식별자는 **MusicBrainz MBID**. 이름이 아니라 MBID를 기준 키로 잡을 것
- 리스트 응답은 `total` / `itemsPerPage` / `page` 로 페이징. `total` 기준으로 반복 호출

### 1.3 응답 구조 (setlist 객체 요지)

```
setlist
├─ id            : 셋리스트 ID
├─ versionId     : 편집 버전 ID  ← 변경 감지 핵심
├─ eventDate     : "dd-MM-yyyy" 형식 문자열 (주의: ISO 아님)
├─ lastUpdated   : 마지막 수정 시각
├─ artist        : { mbid, name, sortName, url }
├─ venue         : { id, name, city: { name, country: { code, name } } }
├─ tour          : { name }              (없을 수 있음)
├─ sets.set[]    : { name, encore, song[] }
│    └─ song[]   : { name, cover:{...}, with:{...}, info, tape }
├─ info          : 공연 관련 메모
└─ url           : setlist.fm 페이지 링크
```

> ⚠️ 위 구조는 공식 문서 기준으로 정리한 것이며, 실제 키 발급 후 응답 1건을 받아 필드 유무(특히 `tour`, `venue.city`, `cover`, `tape`)를 반드시 확인할 것.

### 1.4 반드시 처리해야 할 함정

| 함정 | 대응 |
|------|------|
| `eventDate`가 `dd-MM-yyyy` 문자열 | 파싱 후 DATE 컬럼 저장. 문자열 정렬 금지 |
| 위키 방식이라 셋리스트가 수정됨 (`id` 같아도 내용 다름) | `versionId` 비교로 변경 감지 → 같으면 스킵, 다르면 재적재 |
| 빈 셋리스트 존재 (등록만 되고 곡 없음) | 곡 수 0건은 집계에서 제외 |
| `tape: true` = 실제 연주가 아닌 음원 재생(입/퇴장곡) | 예측 집계에서 **제외** |
| `cover` 필드 = 다른 아티스트 곡 커버 | 별도 플래그로 보관, 예측에는 포함 (실제로 연주하므로) |
| 곡명 표기 흔들림 (대소문자, 괄호, 특수문자) | 정규화 컬럼 별도 생성 후 그걸로 집계 |
| `tour` 누락 빈번 | nullable, 투어 구분은 날짜 기반 보조 로직 병행 |
| Rate limit 존재 | 요청 간 지연 + 429 재시도(백오프). 발급 키의 제한값은 발급 시 안내 확인 |

---

## 2. DB 스키마 (PostgreSQL)

### 2.1 원천 데이터

```sql
-- 아티스트 (setlist.fm/MusicBrainz MBID 기준)
CREATE TABLE artist (
    mbid            UUID PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    sort_name       VARCHAR(200),
    setlist_fm_url  TEXT,
    is_target       BOOLEAN NOT NULL DEFAULT FALSE,  -- MVP 수집 대상 여부
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 공연 (setlist.fm의 setlist 1건 = 공연 1회)
CREATE TABLE show (
    setlist_id      VARCHAR(20) PRIMARY KEY,
    version_id      VARCHAR(20) NOT NULL,            -- 변경 감지 키
    artist_mbid     UUID NOT NULL REFERENCES artist(mbid),
    event_date      DATE NOT NULL,
    tour_name       VARCHAR(200),
    venue_name      VARCHAR(300),
    city_name       VARCHAR(200),
    country_code    CHAR(2),
    show_type       VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',  -- SOLO | FESTIVAL | UNKNOWN
    song_count      SMALLINT NOT NULL DEFAULT 0,
    source_url      TEXT,
    raw_json        JSONB NOT NULL,                  -- 원본 보관 → 재처리 가능
    collected_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_show_artist_date ON show (artist_mbid, event_date DESC);
CREATE INDEX idx_show_type ON show (artist_mbid, show_type, event_date DESC);

-- 공연별 연주곡 (순서 포함)
CREATE TABLE show_song (
    id              BIGSERIAL PRIMARY KEY,
    setlist_id      VARCHAR(20) NOT NULL REFERENCES show(setlist_id) ON DELETE CASCADE,
    set_index       SMALLINT NOT NULL,               -- 0=본편, 1..n=앙코르 순번
    is_encore       BOOLEAN NOT NULL DEFAULT FALSE,
    position_in_set SMALLINT NOT NULL,
    position_total  SMALLINT NOT NULL,               -- 공연 전체 기준 순번
    song_name       VARCHAR(300) NOT NULL,
    song_key        VARCHAR(300) NOT NULL,           -- 정규화된 곡명 (집계 기준)
    is_cover        BOOLEAN NOT NULL DEFAULT FALSE,
    cover_artist    VARCHAR(200),
    is_tape         BOOLEAN NOT NULL DEFAULT FALSE,  -- true면 집계 제외
    note            TEXT
);

CREATE INDEX idx_show_song_key ON show_song (song_key);

-- 재적재는 곡 목록을 통째로 교체하므로 같은 (setlist_id, position_total)을 다시 채운다.
-- Hibernate가 한 flush에서 자식 INSERT를 orphan DELETE보다 먼저 실행하기 때문에
-- 문장 단위 검사로는 아직 지워지지 않은 기존 행과 충돌한다. 검사를 커밋 시점으로 미룬다.
-- UNIQUE INDEX는 DEFERRABLE로 만들 수 없어 테이블 제약으로 선언한다.
ALTER TABLE show_song
    ADD CONSTRAINT uq_show_song UNIQUE (setlist_id, position_total) DEFERRABLE INITIALLY DEFERRED;
```

### 2.2 예측 결과 (배치 사전 계산)

```sql
-- 예측 대상 이벤트 (예: 2026 부산국제록페스티벌 - Megadeth)
CREATE TABLE target_event (
    id                  BIGSERIAL PRIMARY KEY,
    artist_mbid         UUID NOT NULL REFERENCES artist(mbid),
    event_name          VARCHAR(200) NOT NULL,       -- '2026 부산국제록페스티벌'
    event_date          DATE NOT NULL,
    venue_name          VARCHAR(300),
    expected_show_type  VARCHAR(20) NOT NULL,        -- FESTIVAL
    expected_song_count SMALLINT,                    -- 페스티벌 셋 예상 곡 수
    actual_setlist_id   VARCHAR(20) REFERENCES show(setlist_id),  -- 공연 후 채움(적중률 검증)
    UNIQUE (artist_mbid, event_date)
);

-- 곡별 예측 결과
CREATE TABLE prediction (
    id                  BIGSERIAL PRIMARY KEY,
    target_event_id     BIGINT NOT NULL REFERENCES target_event(id) ON DELETE CASCADE,
    song_key            VARCHAR(300) NOT NULL,
    song_name           VARCHAR(300) NOT NULL,
    probability         NUMERIC(5,4) NOT NULL,       -- 0.0000 ~ 1.0000
    rank                SMALLINT NOT NULL,
    played_count        SMALLINT NOT NULL,           -- 근거: 최근 N회 중 연주 횟수
    sample_size         SMALLINT NOT NULL,           -- 근거: N
    avg_position        NUMERIC(4,1),                -- 평균 셋 내 위치
    encore_ratio        NUMERIC(5,4),                -- 앙코르 등장 비율
    evidence            JSONB,                       -- 계산 상세(디버깅/설명용)
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (target_event_id, song_key)
);

CREATE INDEX idx_prediction_rank ON prediction (target_event_id, rank);
```

### 2.3 배치 이력

```sql
CREATE TABLE collection_log (
    id              BIGSERIAL PRIMARY KEY,
    artist_mbid     UUID,
    job_type        VARCHAR(30) NOT NULL,            -- SETLIST_SYNC | PREDICT | EMBED
    status          VARCHAR(20) NOT NULL,            -- SUCCESS | PARTIAL | FAILED
    -- NULL을 허용하면 세 컬럼이 모두 NULL인 행에서 JPA 임베디드가 통째로 null이 되어
    -- 카운트를 읽는 쪽에서 NPE가 난다. 기본값 0을 NOT NULL로 강제한다.
    fetched_count   INT NOT NULL DEFAULT 0,
    updated_count   INT NOT NULL DEFAULT 0,
    skipped_count   INT NOT NULL DEFAULT 0,          -- versionId 동일로 스킵
    error_message   TEXT,
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ
);
```

### 2.4 RAG 저장소

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE rag_document (
    id              BIGSERIAL PRIMARY KEY,
    artist_mbid     UUID REFERENCES artist(mbid),
    song_key        VARCHAR(300),                    -- 곡 단위 문서면 채움
    doc_type        VARCHAR(30) NOT NULL,            -- SONG | ALBUM | ARTIST
    title           VARCHAR(300) NOT NULL,
    source_name     VARCHAR(200) NOT NULL,
    source_url      TEXT NOT NULL,                   -- 출처 표기 필수
    collected_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE rag_chunk (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT NOT NULL REFERENCES rag_document(id) ON DELETE CASCADE,
    chunk_index     SMALLINT NOT NULL,
    content         TEXT NOT NULL,
    embedding       vector(1536) NOT NULL,
    token_count     SMALLINT
);

CREATE INDEX idx_rag_chunk_embedding ON rag_chunk
    USING hnsw (embedding vector_cosine_ops);
```

---

## 3. 곡명 정규화 규칙 (`song_key` 생성)

집계 정확도가 여기서 갈립니다. 최소 규칙:

1. 소문자 변환, 앞뒤 공백 제거
2. 유니코드 정규화(NFKC) — 전각/반각, 특수 따옴표 통일
3. 괄호 부가정보 제거: `(Live)`, `(Acoustic)`, `(Reprise)` 등 화이트리스트 방식으로 제거
4. 구두점 제거 후 공백 1칸으로 압축 (`Bat Country!` → `bat country`)
5. 선행 관사 유지 (`The Stage` ≠ `Stage` 인 경우가 있어 무리한 제거 금지)

> 정규화는 **손실 변환**이므로 `song_name`(원본)과 `song_key`(집계용)를 반드시 분리 저장. 나중에 오탐 발견 시 원본으로 재생성 가능.

---

## 4. 수집 배치 흐름

```
1. artist where is_target = true 조회
2. 아티스트별로 /artist/{mbid}/setlists 페이지 순회
   - 최근 공연부터 반환되므로, 목표 건수(예: 최근 40회) 확보 시 중단
3. 각 setlist에 대해
   - DB의 version_id와 비교
     · 동일 → skip (skipped_count++)
     · 상이/신규 → raw_json 저장 + show / show_song 재적재(트랜잭션)
4. show_type 판정
   - venue/tour 명에 festival 키워드 포함 또는 수동 매핑 테이블 참조
   - 판정 불가 시 UNKNOWN → 가중치 계산에서 별도 취급
5. collection_log 기록
```

주기: 일 1회면 충분. 페스티벌 직후에는 수동 트리거로 즉시 재수집.

---

## 5. 다음 작업 순서

1. setlist.fm 키 발급 → `search/artists`로 대상 밴드 MBID 확보 (Avenged Sevenfold, Megadeth 우선)
2. 셋리스트 응답 1건 실제 수신 → 위 필드 가정 검증, 특히 `tape` / `cover` / `tour` 유무
3. 위 DDL 적용 후 수집 배치 프로토타입 (아티스트 1팀, 최근 40회)
4. 곡명 정규화 규칙을 실데이터로 검증 (중복 song_key 오탐 확인)
5. **8/2 펜타포트 종료 직후** 픽시즈·매시브 어택 셋리스트 수집 → 예측 로직 정답 검증셋으로 사용
