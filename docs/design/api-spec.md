# 인터페이스 정의서 (REST API) — Soundcheck

- 프로젝트: Soundcheck (내한 페스티벌 셋리스트 예측 & 예습 서비스)
- 작성일: 2026-07-30
- 버전: v1.0
- 작성 방식: **구현 완료된 프로토타입을 역설계**하여 문서화 (springdoc/OpenAPI 미도입 — 컨트롤러 코드가 원본).
- Base URL: 동일 오리진 `/api` (개발 시 Vite 프록시 `/api → localhost:8080`)
- 관련 문서: `/docs/design/table-spec.md`, `/docs/design/process-spec.md`, `/docs/design/screen-spec.md`

---

## 1. 공통 규약

### 1.1 인증

| 구분 | 경로 | 방식 |
|------|------|------|
| 공개 API | `/api/**` (admin 제외) | 없음 (permitAll) |
| 관리자 API | `/api/admin/**` | HTTP Basic (InMemory 1계정, STATELESS, CSRF 비활성) |

- 401 응답은 자체 JSON 본문을 반환하며 **`WWW-Authenticate` 헤더를 의도적으로 생략** — 브라우저 네이티브 로그인 팝업 차단.

### 1.2 에러 응답 — RFC 7807 Problem Detail

`spring.mvc.problemdetails.enabled: true`로 프레임워크 에러(404/405/타입 불일치/`@Valid` 실패)까지 통일.

```json
{ "type": "about:blank", "title": "리소스를 찾을 수 없습니다", "status": 404, "detail": "...", "instance": "/api/events/99" }
```

| 상태 | 발생 조건 |
|------|-----------|
| 400 | 잘못된 요청 (`IllegalArgumentException`, Bean Validation 실패, 과거 날짜 이벤트 등록) |
| 401 | 관리자 인증 실패 |
| 404 | 리소스 없음 (`ApiNotFoundException`) |
| 409 | 데이터 충돌 (아티스트·날짜 중복 이벤트, 배치 중복 실행) |

클라이언트는 `detail ?? title`을 사용자 메시지로 사용한다.

### 1.3 공통 표기

- 날짜: `yyyy-MM-dd` (ISO). 확률·비율: 0~1 소수 (표시 변환은 클라이언트 책임).
- `songKey`: 정규화된 곡 키 — 경로에 사용 시 URL 인코딩 필수.

---

## 2. 공개 API

### API-01 아티스트 상세

```
GET /api/artists/{mbid}
```

| 구분 | 내용 |
|------|------|
| 목적 | 예측 화면 상단의 아티스트 수집 통계 |
| 경로 | `mbid` UUID |
| 실패 | 404 미등록 아티스트 |

응답 `ArtistDetailResponse`:

```json
{
  "mbid": "uuid", "name": "Avenged Sevenfold", "sortName": "...", "setlistFmUrl": "...",
  "recentShows": { "total": 32, "festival": 8, "latestEventDate": "2026-07-12", "avgSongCount": 17.3 }
}
```

- `avgSongCount`: 곡 0건 공연 제외 평균, scale 1 HALF_UP, 데이터 없으면 null.

### API-02 공연(이벤트) 목록

```
GET /api/events
```

응답 `EventResponse[]` (공연일 오름차순):

```json
[{ "id": 1, "eventName": "2026 부산국제록페스티벌", "eventDate": "2026-10-02",
   "venueName": "삼락생태공원", "expectedShowType": "FESTIVAL", "verified": false,
   "artist": { "mbid": "uuid", "name": "MEGADETH" } }]
```

- `verified`: `actual_setlist_id` 연결 여부 — true면 화면이 검증 모드로 전환.

### API-03 예측 목록

```
GET /api/events/{id}/predictions
```

| 구분 | 내용 |
|------|------|
| 응답 | `PredictionResponse[]` (rank 오름차순) |
| 실패 | 이벤트 없음 404. **이벤트는 있고 예측이 없으면 404가 아닌 빈 배열** (화면이 "준비 중" 표시) |

```json
[{ "rank": 1, "songKey": "bat country", "songName": "Bat Country", "probability": 0.9612,
   "playedCount": 19, "sampleSize": 20, "avgPosition": 3.2, "encoreRatio": 0.05,
   "recentCount5": 5, "trend": "STABLE" }]
```

- `recentCount5`(최근 5회 중 등장)·`trend`(RISING|STABLE|FALLING, 표본 최근 절반 vs 이전 절반 등장률 차 ±0.2 기준)는 evidence(E4)에서 파생 — v0.2 이전 스냅샷이면 null.

### API-04 예측 상세 (곡 타임라인)

```
GET /api/events/{id}/predictions/{songKey}
```

응답 `PredictionDetailResponse` — 예측 1건 + 표본 공연별 연주 이력(최근순, 예측 표본과 동일 규칙):

```json
{ "prediction": { "...": "API-03과 동일 구조" },
  "confidence": "VERY_HIGH",
  "evidence": { "baseFrequency": 0.95, "weightedScore": 12.3, "totalWeight": 15.6,
                "recencyDecay": 0.95, "matchingShowTypeBoost": 1.5, "boostEffect": 0.04,
                "positionStats": { "opener": 2, "early": 3, "mid": 8, "late": 5, "encore": 1 },
                "typeBreakdown": { "festivalShows": 12, "festivalPlayed": 9, "soloShows": 5, "soloPlayed": 2 } },
  "history": [{ "setlistId": "...", "eventDate": "2026-07-12", "venueName": "...", "cityName": "...",
                "showType": "FESTIVAL", "playedSongCount": 18, "played": true, "position": 3, "encore": false,
                "weight": 1.43 }] }
```

- `confidence`(E1): 표본 크기 × 확률 규칙 라벨 — VERY_HIGH(표본 ≥ 15 & 확률 ≥ 0.9) / HIGH(≥ 0.7) / MEDIUM(≥ 0.4) / LOW(그 외 또는 표본 < 8).
- `evidence`(E1/E3/E4): 확률 분해 블록. `boostEffect` = 확률 − 부스트 없는 확률. v0.2 이전 스냅샷(evidence 미저장)이면 블록 전체가 null, 확장 필드만 없으면 해당 필드 null.
- `history[].weight`: 해당 공연이 계산에 기여한 가중치 — 미연주 공연은 null.

### API-04b 유사 공연 (E11)

```
GET /api/events/{id}/similar-shows
```

과거 공연 중 예측 대상과 가장 비슷한 상위 3건 + 셋리스트. 점수 = 유형 일치(0.4) + 시기 근접(0.3, 반감기 365일) + 예측 상위 20곡과 실제 셋의 Jaccard(0.3). 이벤트 없음 404, 비교할 공연 없으면 빈 배열.

```json
{ "shows": [{ "setlistId": "...", "eventDate": "2026-07-27", "venueName": "...", "cityName": "...",
              "showType": "FESTIVAL", "score": 0.8712, "typeMatch": true, "overlapCount": 14,
              "setlist": [{ "position": 1, "songName": "...", "encore": false }] }] }
```

### API-04c 재생목록 — 묶음 듣기 (E12)

```
POST /api/events/{id}/playlist
{ "songKeys": ["holy wars", "trust"] }
```

선택한 예측 곡을 YouTube 임시 재생목록(`youtube.com/watch_videos?video_ids=…`, 비공식 엔드포인트) 링크로 만든다. POST인 이유: 캐시 미스 곡은 YouTube Data API 검색(쿼터 100유닛/건)이 실행된다.

| 구분 | 내용 |
|------|------|
| 비용 가드 | **예측 목록에 있는 곡만** 검색(임의 곡명 쿼터 유출 방지), 결과는 실패까지 `song_video`에 캐시, 최대 50곡 |
| 실패 | 이벤트 없음 404 / `YOUTUBE_API_KEY` 미설정 503 / 곡 0개·50곡 초과 400 |

```json
{ "url": "https://www.youtube.com/watch_videos?video_ids=abc,def",
  "songs":   [{ "songKey": "holy wars", "songName": "Holy Wars", "videoTitle": "Megadeth - Holy Wars (Live)" }],
  "missing": [{ "songKey": "rare song", "songName": "Rare Song", "videoTitle": null }] }
```

- `url` null = 해석된 영상이 하나도 없음. 재생 순서는 요청한 songKeys 순서.

### API-05 적중률 (단건)

```
GET /api/events/{id}/accuracy
```

| 실패 | 이벤트 없음/실제 셋 미연결 → 404 |

응답 `AccuracyResponse`:

```json
{ "actualSongCount": 15, "topK": 15, "topKHits": 11, "precisionAtK": 0.7333,
  "totalHits": 13, "recall": 0.8667, "f1": 0.7945,
  "top5": { "size": 5, "hits": 4, "accuracy": 0.8 },
  "top10": { "size": 10, "hits": 8, "accuracy": 0.8 },
  "results": [{ "rank": 1, "songKey": "...", "songName": "...", "probability": 0.9612, "played": true, "actualPosition": 3 }],
  "surprises": [{ "songName": "...", "actualPosition": 7 }] }
```

- `results`: 예측 전체의 적중 여부. `surprises`: 예측에 없던 실연주 곡.
- `f1` = 2·P·R/(P+R) (P=precisionAtK, R=recall, 둘 다 0이면 0). `top5`/`top10`: 상위 N곡 성적 — 예측이 N곡 미만이면 `size`가 분모.

### API-06 적중률 아카이브

```
GET /api/events/accuracy
```

응답 `AccuracySummaryResponse[]` (최근순, 예측이 없던 이벤트 제외):

```json
[{ "eventId": 2, "eventName": "...", "eventDate": "2026-08-01", "artistMbid": "uuid", "artistName": "Pixies",
   "actualSongCount": 15, "topK": 15, "topKHits": 11, "precisionAtK": 0.7333, "f1": 0.7945,
   "top5Hits": 4, "top5Size": 5, "top10Hits": 8, "top10Size": 10 }]
```

### API-04a 예상 셋리스트 (E6)

```
GET /api/events/{id}/expected-setlist
```

본편/앙코르 블록 구조. 저장하지 않고 조회 시 `SetlistComposer`(순수 함수)가 구성한다. 이벤트 없음 404, 예측 전이면 빈 블록.

```json
{ "expectedSongCount": 18,
  "main":   [{ "order": 1, "songKey": "opener song", "songName": "Opener Song", "probability": 0.9 }],
  "encore": [{ "order": 18, "songKey": "encore hit", "songName": "Encore Hit", "probability": 0.8 }] }
```

- 곡 수 = 유형별 평균 곡 수(없으면 전체 평균, 그마저 없으면 확률 ≥ 0.5 곡 수) 반올림.
- 구성: 확률 상위 선발 → 앙코르 = encoreRatio ≥ 0.5(비율 내림차순, 최대 3) → 오프너 = 오프너 비율 최상위 고정 → 본편 = 평균 위치 오름차순. 동률은 rank로 결정적.

### API-06a 곡 장기 통계 (E5)

```
GET /api/artists/{mbid}/songs/{songKey}/stats
```

예측 표본(최근 20회)과 달리 **수집된 전체 공연** 대상 집계. tape 곡은 등장으로 세지 않고, 곡 0건 공연은 분모에서 제외. 아티스트 없음 또는 연주 기록 없는 곡 → 404.

```json
{ "yearly": [{ "year": 2025, "totalShows": 41, "playedShows": 38 }],
  "tours":  [{ "tourName": null, "totalShows": 12, "playedShows": 9 }],
  "types":  [{ "showType": "FESTIVAL", "totalShows": 12, "playedShows": 9 }] }
```

- `tours[].tourName` null = 투어 없는 공연 묶음(원본 표기 그대로 group by, 공연 수 내림차순 — 화면은 상위 5개만).

### API-06b 아티스트 활동 통계 (E5)

```
GET /api/artists/{mbid}/stats
```

```json
{ "yearly": [{ "year": 2025, "showCount": 41, "avgSongCount": 17.2 }],
  "typeDistribution": { "festival": 12, "solo": 20, "unknown": 9 } }
```

### API-07 곡 설명 (RAG, SSE)

```
GET /api/songs/{songKey}/explanation?artistMbid={uuid}&songName={원본 곡명}
Accept: text/event-stream
```

| 구분 | 내용 |
|------|------|
| 목적 | 곡 배경 설명 스트리밍 (곡 이야기) |
| 사전 검증 | 미등록 아티스트 404 / **예측에 등장하지 않는 songKey 404** (임의 키로 LLM 비용 유출 방지) |
| 캐시 | 히트 시 LLM 미호출, 저장 본문 즉시 전송 |

**SSE 이벤트 계약** (순서 보장):

| event | data | 비고 |
|-------|------|------|
| `sources` | `[{ "name": "Wikipedia(en)", "url": "...", "title": "..." }]` | 본문 `[n]` 표기와 순서 일치 |
| `delta` | `"텍스트 조각"` — **JSON 문자열로 감쌈** | SSE가 데이터 선행 공백을 제거하므로 인코딩 필수 |
| `done` | `{}` | 정상 종료 |
| `error` | 오류 메시지 | 연결을 끊지 않고 이벤트로 통지 — 클라이언트는 수신 즉시 `close()` (자동 재연결 방지) |

- 근거 문서가 없거나 유사도 미달이면 본문이 정확히 `"정보 없음"` 1건 — 클라이언트 빈 상태 판별 문자열.

### API-07a RAG Chat (E8, SSE)

```
POST /api/events/{id}/chat
Content-Type: application/json / Accept: text/event-stream
{ "messages": [{ "role": "user", "content": "꼭 들어야 하는 곡은?" }] }
```

| 구분 | 내용 |
|------|------|
| 방식 | tool calling(searchDocs=배경 문서 검색, getPredictionStats=예측 조회) 스트리밍. 도구 결과 밖 내용 생성 금지 프롬프트 |
| 이력 | 클라이언트가 이전 메시지를 함께 전송(stateless). 서버는 최근 12메시지(6턴)만 사용 |
| 검증 | 이벤트 없음 404 / 마지막 메시지가 비어 있거나 user가 아니면 400 / 질문 500자 초과 400 |
| 비용 가드 | IP·이벤트당 분당 5회(429), `llm_call_log`(CHAT) 기록 |
| SSE 순서 | `delta`* → `sources` → `done` — **출처가 도구 실행 후 확정되므로 곡 설명과 순서가 다르다**. 실패는 `error` 이벤트 |
| 출처 | 문서 도구 사용 시 URL 목록, 통계 도구 사용 시 `{name: "Soundcheck", url: "", title: "예측 데이터 기준"}` |

EventSource는 POST 불가 — 프론트는 fetch 스트림으로 SSE를 파싱한다(`useChat`).

---

## 3. 관리자 API (`/api/admin/**`, Basic 인증)

### API-08 등록 아티스트 목록

```
GET /api/admin/artists
```

응답: `[{ "mbid": "uuid", "name": "...", "target": true }]`

### API-09 아티스트 검색 (setlist.fm 프록시)

```
GET /api/admin/artists/search?name={검색어}
```

응답: `[{ "mbid": "uuid", "name": "...", "sortName": "...", "disambiguation": "...", "url": "...", "alreadyRegistered": false }]`

- 검색 첫 결과가 본체라는 보장이 없어(`disambiguation` 병기) 운영자가 후보 중 선택한다.

### API-10 아티스트 등록

```
POST /api/admin/artists
```

요청: `{ "mbid": "uuid"(필수), "name": "..."(필수), "sortName": "...", "setlistFmUrl": "..." }`
응답: **201** `{ "mbid", "name", "target": true }` — 기존 존재 시 프로필 갱신 + 수집 대상 복귀 (멱등).

### API-11 이벤트 등록 (+ 즉시 예측)

```
POST /api/admin/events
```

요청:

```json
{ "artistMbid": "uuid", "eventName": "2026 부산국제록페스티벌", "eventDate": "2026-10-02",
  "venueName": "삼락생태공원", "expectedShowType": "FESTIVAL", "expectedSongCount": 12 }
```

| 응답 | **201** `{ "id": 3, "predictionStatus": "SUCCESS" \| "FAILED" }` — 등록 직후 매칭+예측을 동기 실행한 결과 |
| 실패 | 400 과거 날짜 (사후 예측 방지) / 404 아티스트 미등록 / 409 같은 아티스트·날짜 중복 |

### API-12 수집 배치 트리거

```
POST /api/admin/batch/collect
```

응답: **202** `{ "started": true }` — 비동기 시작. 이미 실행 중이면 **409** Problem.

### API-13 예측 재계산

```
POST /api/admin/batch/predict
```

응답: **200** `LogEntry[]` — **동기 실행** 후 이번 실행의 이벤트별 로그 반환.

### API-14 RAG 문서 수집 트리거

```
POST /api/admin/batch/rag-ingest
```

응답: **202** `{ "started": true }` / 실행 중이면 **409**. (수집 배치와 별도 락)

### API-14a AI 사용량 대시보드 (E9)

```
GET /api/admin/ai-dashboard
```

오늘(KST 자정 이후) 기준 LLM 사용량. 예상 비용은 설정 단가(`encore.llm.cost.*`) × 토큰.

```json
{ "totalCalls": 42, "cacheHitRate": 0.6190, "inputTokens": 15000, "outputTokens": 4200,
  "embeddingTokens": 8000, "estimatedCostUsd": 0.0049,
  "byType": [{ "callType": "EXPLANATION", "calls": 21, "avgLatencyMs": 2100,
               "inputTokens": 0, "outputTokens": 0, "cacheHits": 13, "errors": 0 }] }
```

- 스트리밍 호출(EXPLANATION·CHAT)은 usage 메타데이터가 없어 토큰이 NULL로 기록될 수 있다 — 토큰 합계는 기록된 값만 합산한 하한치.

### API-14b RAG 저장소 관리 (E10)

```
GET    /api/admin/rag/status                — 수집 대상 아티스트별 문서/청크/캐시 수 + 마지막 EMBED 실행
GET    /api/admin/rag/documents?artistMbid= — 문서 목록(제목/URL/doc_type/청크 수)
DELETE /api/admin/rag/documents/{id}        — 문서 삭제(청크 FK CASCADE + 아티스트 설명 캐시 무효화)
DELETE /api/admin/rag/cache/{artistMbid}    — 설명 캐시 아티스트 단위 무효화
```

### API-15 배치 이력

```
GET /api/admin/logs
```

응답:

```json
{ "collecting": false, "ragIngesting": false,
  "logs": [{ "id": 41, "jobType": "SETLIST_SYNC", "status": "PARTIAL", "artistMbid": "uuid",
             "fetched": 40, "updated": 3, "skipped": 37, "errorMessage": null,
             "startedAt": "...", "finishedAt": "..." }] }
```

- `logs` 최근 30건. `collecting`/`ragIngesting`은 락 상태(진행 중 여부) — collection_log에는 진행 중 상태가 없으므로 여기서만 확인 가능. 프론트는 수집 중 3초/평시 15초 폴링.
- 카운트의 job_type별 의미는 테이블설계서 §4.7.

### API-16 내한 공연 감지

```
GET /api/admin/korea-shows
```

응답: `[{ "setlistId": "...", "artistMbid": "uuid", "artistName": "...", "eventDate": "2026-10-03", "venueName": "...", "cityName": "...", "showType": "FESTIVAL", "alreadyRegistered": false }]`

- 수집된 공연 중 `country_code = 'KR'` 후보 → 원클릭 이벤트 등록(API-11)으로 연결.

---

## 4. 엔드포인트 요약표

| # | 메서드 | 경로 | 인증 | 응답 | 소비 화면 |
|---|--------|------|:----:|------|-----------|
| 01 | GET | `/api/artists/{mbid}` | — | 아티스트 + 수집 통계 | SC-02 |
| 02 | GET | `/api/events` | — | 이벤트 목록 | SC-01, SC-02 |
| 03 | GET | `/api/events/{id}/predictions` | — | 예측 목록 (rank순) | SC-02 |
| 04 | GET | `/api/events/{id}/predictions/{songKey}` | — | 예측 상세 + 타임라인 | SC-03 |
| 05 | GET | `/api/events/{id}/accuracy` | — | 적중률 상세 | SC-02 (검증 모드) |
| 06 | GET | `/api/events/accuracy` | — | 적중률 아카이브 | SC-01 |
| 07 | GET | `/api/songs/{songKey}/explanation` | — | **SSE** 곡 설명 | SC-03 |
| 08 | GET | `/api/admin/artists` | Basic | 등록 아티스트 | SC-04 |
| 09 | GET | `/api/admin/artists/search` | Basic | setlist.fm 검색 | SC-04 |
| 10 | POST | `/api/admin/artists` | Basic | 201 등록 | SC-04 |
| 11 | POST | `/api/admin/events` | Basic | 201 + 예측 결과 | SC-04 |
| 12 | POST | `/api/admin/batch/collect` | Basic | 202 / 409 | SC-04 |
| 13 | POST | `/api/admin/batch/predict` | Basic | 200 로그 | SC-04 |
| 14 | POST | `/api/admin/batch/rag-ingest` | Basic | 202 / 409 | SC-04 |
| 15 | GET | `/api/admin/logs` | Basic | 이력 + 진행 상태 | SC-04 |
| 16 | GET | `/api/admin/korea-shows` | Basic | KR 공연 후보 | SC-04 |

## 5. 설계 결정 기록

| 결정 | 이유 |
|------|------|
| 이벤트 단건 조회 API 없음 | 목록이 작아 클라이언트가 목록 캐시에서 select — 단, 직접 URL 진입 시 헤더 무음 실패가 있어 신설 검토 중 (화면설계서 §8) |
| 예측 없음 = 빈 배열 (404 아님) | "이벤트는 존재하나 예측 미계산" 상태를 화면이 구분해 안내 |
| SSE `delta`를 JSON 문자열로 감쌈 | SSE 규격이 data 선행 공백 1개를 제거 — 마크다운/공백 보존 |
| `error`를 이벤트로 통지 | 연결 오류로 처리하면 EventSource가 자동 재연결 → LLM 중복 호출 |
| 곡 설명 사전 검증 404 | 예측에 없는 임의 songKey로 임베딩/LLM 비용 유출 방지 |
| 배치 트리거 202 + 폴링 | 수집은 분 단위 소요 — 요청을 붙잡지 않고 이력 API로 진행 확인 |
