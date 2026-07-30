# 확장 기능 구현 계획 (PRD v0.2 §4.3)

- 작성일: 2026-07-30
- 전제: MVP(F1~F5) + 정확도 평가 구현 완료 상태. 항목 번호(E1~E11)는 PRD §4.3 기준.
- 원칙: 새 계산 로직은 `PredictionCalculator`·`AccuracyCalculator`처럼 **순수 함수 + 단위 테스트**로 만들고, 스키마 변경 시 Flyway 마이그레이션 + `/docs/setlist-schema.md` 갱신을 함께 한다.

## 단계 개요

| 단계 | 기간(안) | 내용 | 검증 포인트 |
|------|----------|------|-------------|
| P1 | 1~2주 (8월 초·중순) | 기존 저장 데이터 재사용: E1 근거 노출, E2 지표 확장, E3 위치 분석, E4 수치부 | 8/1~8/2 펜타포트 채점 결과에 F1·Top-N 즉시 적용 |
| P2 | 2~3주 (8월 중·하순) | 사용자 가치: E5 통계·차트, E6 셋리스트 생성, E7 예습 코스, E4 LLM 요약 | 부산 록페 이벤트 화면으로 실사용 검증 |
| P3 | 2~3주 (9월) | AI·운영 심화: E8 RAG Chat, E9 계측+대시보드, E10 Admin 확장, E11 유사 공연 | 10/2 공개 전 폴리싱 기간 확보 |

---

## P1 — 기존 데이터 재사용 (스키마 변경 최소)

### E1. Explainable AI — evidence API 노출

이미 `prediction.evidence`(JSONB)에 저장된 데이터를 꺼내 쓰는 작업. 새 수집·계산 없음.

- **Backend**
  - `PredictionDetailResponse`에 근거 블록 추가: `baseFrequency`(부스트 없는 단순 등장률), `weightedScore/totalWeight`, 공연별 `appearances[].weight`, 유형 부스트가 확률에 기여한 정도(부스트 있/없 확률 차).
  - 신뢰도 라벨 규칙을 순수 함수로: 예) `sampleSize ≥ 15 && probability ≥ 0.9` → VERY_HIGH … `sampleSize < 8` → LOW. 규칙은 상수로 두고 단위 테스트.
  - evidence JSON 역직렬화는 `PredictionGenerator`가 쓰는 직렬화 모델을 공유해 스키마 어긋남 방지.
- **Frontend**: `SongPage` 기존 타임라인에 공연별 가중치 표시(막대 굵기/투명도), "왜 96%인가" 근거 카드(등장률 × 최신성 × 유형 부스트 분해 + 신뢰도 라벨).
- **Test**: 부스트 기여 분해 검증(부스트 1.0일 때 기여 0), 신뢰도 경계값.

### E2. 정확도 지표 확장 — F1, Top-N

- **Backend**: `AccuracyCalculator`에 `f1()`(= 2·P·R/(P+R), Precision@K·Recall 기반), `topNAccuracy(n)`(상위 N곡 중 적중 비율, N=5·10) 추가. `AccuracyResponse`/`AccuracySummaryResponse` 확장.
- **Frontend**: `EventListPage` 성적 아카이브와 `PredictionsPage` 적중률 카드에 F1·Top-5/10 표기.
- **Docs**: `tuning-log.md` 지표 정의 표에 F1·Top-N 추가.
- **Test**: P=0 or R=0일 때 F1=0(0 나눗셈 방지), K < N인 짧은 셋 경계.

### E3. 셋리스트 위치 분석 — 구간 비율

- **정의**: 곡별로 표본 공연 내 위치를 오프너(position_total=1) / 초반·중반·후반(본편을 3분위) / 앙코르(is_encore)로 분류해 비율 계산. 분모는 해당 곡이 등장한 공연 수.
- **Backend**: `PredictionCalculator`가 이미 공연별 `positionTotal`·`encore`를 evidence에 수집하므로 **계산 확장으로 해결**(새 쿼리 불필요). 결과는 `prediction`에 JSONB 컬럼 `position_stats` 추가(V8 마이그레이션)하거나 evidence 내부에 포함 — evidence 내부 포함을 우선(스키마 변경 없음), 조회 빈도가 문제되면 컬럼 승격.
- **Frontend**: `SongPage`에 구간 비율 표시(오프너 0% / 중반 15% / 후반 72% / 앙코르 13%).
- **Test**: 본편 3분위 경계(곡 수 1·2·3곡 공연), 앙코르만 있는 곡.

### E4(수치부). 변화 분석 — 최근 5회·추이·유형별 차이

> 구현 노트: `appearances[]`에는 곡이 **연주된 공연만** 들어 있어(미등장 공연 없음) evidence 파싱만으로는
> 아래 수치를 만들 수 없었다. 대신 `PredictionCalculator`가 표본 전체를 입력으로 받으므로
> 계산 시점에 파생해 evidence에 저장하는 방식으로 구현했다(E3도 동일). 구 스냅샷은 해당 필드 null.

- **Backend**: 표본 전체(미등장 공연 포함)에서 파생:
  - `recentCount5`: 최근 5회 공연 중 등장 횟수
  - `trend`: 표본 전반 10회 vs 후반 10회 등장률 차 → RISING / STABLE / FALLING
  - `festivalRate` vs `soloRate`: 표본 내 show_type별 등장률 (UNKNOWN은 제외하고 분모 표기)
  - `PredictionResponse`에 `recentCount5`·`trend`, `PredictionDetailResponse`에 유형별 등장률 추가.
- **Frontend**: 예측 목록에 추이 배지(↑/↓), 곡 상세에 유형별 등장률.
- **Test**: 표본 20회 미만일 때 전·후반 분할, 유형 편중 표본(전부 FESTIVAL)일 때 solo 분모 0 처리.

**P1 산출물**: API 변경만으로 화면 근거·지표가 풍부해짐. 펜타포트 3건 채점(8/1~8/3)에 F1·Top-N 적용해 `tuning-log.md`에 기록.

---

## P2 — 사용자 가치

### E5. 셋리스트 통계 API + 차트

- **Backend**: `StatsController` 신설.
  - `GET /api/artists/{mbid}/songs/{songKey}/stats`: 연도별 등장률(show_song ⨝ show, `date_trunc('year')` group by), 투어별 등장률(`tour_name` group by, null은 "투어 없음"), 유형별 등장률.
  - `GET /api/artists/{mbid}/stats`: 아티스트 요약(연도별 공연 수, 유형 분포, 평균 곡 수 추이).
  - 집계는 표본 제한 없이 전체 수집 데이터 대상(예측 표본 20회와 구분 명시). 네이티브 쿼리 + 인덱스는 기존 `(artist, date desc)` 활용.
- **Frontend**: Recharts 도입(신규 의존성). `SongPage`에 연도별 추이 라인, 투어별 등장률 바 차트. 모바일 우선이므로 차트는 2종만.
- **Test**: 집계 쿼리는 Testcontainers/H2 대신 기존 테스트 DB 방식 따름. tape 곡 제외 확인.

### E6. 예상 셋리스트 백엔드 생성

- **Backend**: `SetlistComposer` 순수 함수 신설.
  - 입력: 예측 목록(확률·avgPosition·encoreRatio·position_stats), `expected_show_type`, 아티스트 유형별 평균 곡 수.
  - 로직: ① 예상 곡 수 = 유형별 평균(FESTIVAL이면 페스티벌 공연 평균, 없으면 전체 평균)을 반올림 ② 앙코르 블록 = encoreRatio 상위 곡(비율 ≥ 0.5, 최대 3곡) ③ 오프너 = 오프너 비율 최상위 곡 고정 ④ 본편 = 남은 확률 상위 곡을 avgPosition 정렬.
  - API: `GET /api/events/{id}/expected-setlist` → 본편/앙코르 블록 구조 응답. 저장하지 않고 조회 시 계산(예측이 이미 사전 계산되어 있어 가벼움).
- **Frontend**: `PredictionsPage` "예상 순서" 토글을 백엔드 응답으로 교체(`buildExpectedSetlist` 프론트 로직 제거), 본편/Encore 블록 UI.
- **Test**: 앙코르 후보 0곡·페스티벌 평균 곡 수 없음 등 결측 경로, 결정적 정렬.

### E7. 예습 코스 추천

- **정책**: 곡 길이 데이터가 없으므로 v1은 곡당 평균 4.5분 가정(30분 코스 ≈ 6곡, 1시간 ≈ 13곡, 2시간 ≈ 26곡). 곡 구분은 확률 구간: 필수(≥ 0.8) / 추천(0.5~0.8) / 심화(< 0.5 중 encoreRatio·오프너 비율 높은 곡 우선).
- **구현**: 백엔드 계산 불필요 — 기존 예측 응답으로 프론트 `lib/practice.ts` 확장. 추천 이유는 규칙 기반 문구("최근 20회 중 19회 연주된 고정곡", "앙코르 단골") — E1의 근거 데이터 재사용. LLM 이유 생성은 채택하지 않음(규칙 문구가 근거 추적 가능하고 비용 0).
- **Frontend**: `PredictionsPage` 예습 체크리스트를 코스 선택(30분/1시간/2시간) UI로 확장, localStorage 유지.

### E4(요약부). LLM 변화 요약

- **Backend**: 예측 재계산 시 변화 데이터(trend가 RISING/FALLING인 곡, 최근 5회 신규 진입/이탈 곡)를 구조화해 프롬프트로 전달 → 2~3문장 한국어 요약. **통계에 없는 내용 생성 금지** 프롬프트(기존 `ExplanationPrompts` 패턴).
  - 캐시: `target_event`에 `trend_summary` TEXT + `trend_summary_at` 컬럼(V8). 예측 재계산 시에만 갱신 — 조회당 LLM 호출 없음.
  - 변화 곡이 없으면 LLM 호출 없이 null(화면 미표시).
- **Frontend**: `PredictionsPage` 상단에 요약 문장 표시.
- **Test**: 프롬프트 입력 구조화 함수 단위 테스트(요약 자체는 수동 평가).

---

## P3 — AI·운영 심화

### E9. LLM 계측 (E8보다 먼저 — Chat 비용을 처음부터 기록)

- **Backend**: `llm_call_log` 테이블(V9): `call_type`(EXPLANATION | CHAT | TREND_SUMMARY | EMBEDDING), `model`, `input_tokens`, `output_tokens`, `latency_ms`, `cache_hit`, `error`, `created_at`.
  - Spring AI 응답 메타데이터(usage)에서 토큰 추출, 공통 래퍼(또는 Advisor)로 기록. 캐시 히트는 `SongExplanationCache` 반환 지점에서 기록.
  - `GET /api/admin/ai-dashboard`: 오늘 호출 수, 평균 응답 시간, 캐시 히트율, 토큰 합, 예상 비용(모델 단가 상수 × 토큰 — 단가는 설정값으로), 임베딩 건수.
- **Frontend**: `AdminPage`에 AI 대시보드 섹션(숫자 카드 위주, 차트 불필요).
- **Test**: 비용 계산 함수, 집계 쿼리.

### E8. RAG Chat

- **Backend**: `POST /api/events/{id}/chat` SSE 스트리밍(기존 곡 설명 SSE 패턴 재사용).
  - Spring AI tool calling으로 도구 2개: ① `searchDocs(query)` — 기존 `RagChunkRepository.search()` 재사용(아티스트 필터) ② `getPredictionStats(songKey?)` — 예측·통계 조회. 도구 결과 밖 내용 생성 금지 + 출처 목록 반환(문서 도구 사용 시 URL, 통계 도구 사용 시 "예측 데이터 기준" 표기).
  - 대화 이력: 클라이언트가 이전 메시지를 함께 전송하는 stateless 방식(서버 세션 저장 없음, 최근 6턴 제한).
  - 비용 가드: IP·이벤트당 분당 요청 제한, 질문 길이 제한, `llm_call_log` 기록.
- **Frontend**: `PredictionsPage`(또는 별도 탭)에 채팅 UI, 예시 질문 칩("꼭 들어야 하는 곡은?", "신곡 나올 가능성은?").
- **평가**: PRD §8 방식대로 대표 질문 20개 셋으로 수동 평가 → `tuning-log.md`에 기록.

### E10. Admin 확장

- 임베딩 상태: `GET /api/admin/rag/status` — 아티스트별 rag_document/rag_chunk 수, 마지막 EMBED 로그.
- 캐시 상태: song_explanation 건수 + 아티스트 단위 무효화 버튼(기존 `evictArtist()` 노출).
- RAG 문서 관리: 문서 목록(제목/URL/doc_type/청크 수) + 삭제(삭제 시 해당 청크·캐시 연쇄 정리).

### E11. 유사 공연 분석

- **Backend**: `GET /api/events/{id}/similar-shows` — 대상 아티스트의 과거 공연을 점수화: 유형 일치(0.4) + 시기 근접(0.3, 공연일 차이 감쇠) + 곡 구성 겹침(0.3, 예측 상위 K와 실제 셋의 Jaccard). 상위 3건 + 해당 공연 셋리스트 반환. 순수 함수 + 단위 테스트.
- **Frontend**: `PredictionsPage` 하단 "참고할 만한 최근 공연" 카드.
- 우선순위 최하 — P3에서 시간이 남으면 진행, 아니면 공개 후 과제.

---

## 스키마 변경 요약

| 마이그레이션 | 내용 | 단계 |
|--------------|------|------|
| V8 | `target_event.trend_summary`, `trend_summary_at` (+ 필요 시 `prediction.position_stats` 승격) | P2 |
| V9 | `llm_call_log` 테이블 | P3 |

각 마이그레이션 시 `/docs/setlist-schema.md` 동시 갱신.

## 리스크·의존성

- **E4 유형별 등장률**: `ShowTypes.classify()`가 SOLO를 자동 판정하지 않아 표본 대부분이 UNKNOWN일 수 있음 → P1 착수 시 실제 데이터의 유형 분포부터 확인하고, UNKNOWN 비중이 크면 "페스티벌 vs 비페스티벌"로 표기 단순화.
- **E5 투어별 통계**: `tour_name` 표기가 setlist.fm에서 일관되지 않을 수 있음(같은 투어 다른 표기) → 원본 표기 그대로 group by 하되 화면에서 상위 N개 투어만 노출.
- **E8/E9 비용**: Chat은 캐시가 불가능한 자유 질의라 P3 중 유일한 변동 비용원 → rate limit과 `llm_call_log` 모니터링을 같은 단계에 묶은 이유.
- **펜타포트 채점 일정(8/1~8/3)**: E2를 P1 최우선으로 두어 첫 실전 채점부터 F1·Top-N을 기록.
