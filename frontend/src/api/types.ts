// 백엔드 응답 타입 — com.encore.api의 응답 DTO와 1:1 대응.

export type ShowType = 'SOLO' | 'FESTIVAL' | 'UNKNOWN'

export interface EventSummary {
  id: number
  eventName: string
  eventDate: string // ISO "2026-10-02"
  venueName: string | null
  expectedShowType: ShowType
  /** 실제 셋리스트가 연결됨 — 적중률 조회 가능 */
  verified: boolean
  /** 예측 변화 LLM 요약(E4) — 변화 없음/생성 전이면 null */
  trendSummary: string | null
  artist: {
    mbid: string
    name: string
  }
}

/** 표본 최근 절반 vs 이전 절반 등장률 변화 (E4). */
export type Trend = 'RISING' | 'STABLE' | 'FALLING'

export interface Prediction {
  rank: number
  songKey: string
  songName: string
  probability: number // 0..1
  playedCount: number
  sampleSize: number
  avgPosition: number | null
  encoreRatio: number | null
  /** 최근 5회 공연 중 등장 횟수 — v0.2 이전 스냅샷이면 null */
  recentCount5: number | null
  trend: Trend | null
}

export interface ArtistDetail {
  mbid: string
  name: string
  sortName: string | null
  setlistFmUrl: string | null
  recentShows: {
    total: number
    festival: number
    latestEventDate: string | null
    avgSongCount: number | null
  }
}

/** 예측 신뢰도 라벨 — 표본 크기 × 확률 규칙 (E1). */
export type Confidence = 'VERY_HIGH' | 'HIGH' | 'MEDIUM' | 'LOW'

/** 셋리스트 내 위치 구간별 등장 횟수 — 합계 = playedCount (E3). */
export interface PositionStats {
  opener: number
  early: number
  mid: number
  late: number
  encore: number
}

/** 표본 내 공연 유형별 공연 수와 등장 횟수 — UNKNOWN 제외 (E4). */
export interface TypeBreakdown {
  festivalShows: number
  festivalPlayed: number
  soloShows: number
  soloPlayed: number
}

/** "왜 이 확률인가" — 등장률 × 최신성 × 유형 부스트 분해 (E1). */
export interface EvidenceBlock {
  baseFrequency: number
  weightedScore: number
  totalWeight: number
  recencyDecay: number
  matchingShowTypeBoost: number
  /** 유형 부스트가 확률에 기여한 정도(%p 아님, 0..1 차이). 구버전 스냅샷이면 null */
  boostEffect: number | null
  positionStats: PositionStats | null
  typeBreakdown: TypeBreakdown | null
}

/** 곡 상세 — 예측 근거 + 최근 공연 타임라인(연주/미연주 포함, 최근순). */
export interface PredictionDetail {
  prediction: Prediction
  confidence: Confidence
  /** v0.2 이전 스냅샷(evidence 미저장)이면 null */
  evidence: EvidenceBlock | null
  history: {
    setlistId: string
    eventDate: string
    venueName: string | null
    cityName: string | null
    showType: ShowType
    playedSongCount: number
    played: boolean
    position: number | null
    encore: boolean | null
    /** 이 공연이 확률 계산에 기여한 가중치 — 미연주면 null */
    weight: number | null
  }[]
}

/** 상위 N곡 성적 — 예측이 N곡보다 적으면 size가 분모. */
export interface TopNAccuracy {
  size: number
  hits: number
  accuracy: number
}

/** 공연 후 예측 vs 실제 비교. precisionAtK가 헤드라인: 상위 K곡 예습 시 적중 비율. */
export interface AccuracyReport {
  actualSongCount: number
  topK: number
  topKHits: number
  precisionAtK: number
  totalHits: number
  recall: number
  /** Precision@K와 Recall의 조화 평균 */
  f1: number
  top5: TopNAccuracy
  top10: TopNAccuracy
  results: {
    rank: number
    songKey: string
    songName: string
    probability: number
    played: boolean
    actualPosition: number | null
  }[]
  surprises: {
    songName: string
    actualPosition: number
  }[]
}

/** 적중률 아카이브 항목 — 검증된 지난 공연의 예측 성적. */
export interface AccuracySummary {
  eventId: number
  eventName: string
  eventDate: string
  artistMbid: string
  artistName: string
  actualSongCount: number
  topK: number
  topKHits: number
  precisionAtK: number
  f1: number
  top5Hits: number
  top5Size: number
  top10Hits: number
  top10Size: number
}

/** 예상 셋리스트(E6) — 본편/앙코르 블록. order는 본편에 이어지는 전체 순번. */
export interface ExpectedSetlist {
  expectedSongCount: number
  main: { order: number; songKey: string; songName: string; probability: number }[]
  encore: { order: number; songKey: string; songName: string; probability: number }[]
}

/** 곡의 장기 통계(E5) — 예측 표본(최근 20회)과 달리 수집된 전체 공연 대상. */
export interface SongStats {
  yearly: { year: number; totalShows: number; playedShows: number }[]
  /** tourName null = 투어 없는 공연 묶음. 공연 수 내림차순 */
  tours: { tourName: string | null; totalShows: number; playedShows: number }[]
  types: { showType: ShowType; totalShows: number; playedShows: number }[]
}

/** 아티스트 활동 요약(E5). */
export interface ArtistStats {
  yearly: { year: number; showCount: number; avgSongCount: number | null }[]
  typeDistribution: { festival: number; solo: number; unknown: number }
}

/** 곡 배경 설명의 출처 — RAG 응답에 항상 함께 온다. */
export interface ExplanationSource {
  name: string
  url: string
  title: string
}

/** RFC 7807 Problem Detail — 에러 응답 공통 형식. */
export interface ProblemDetail {
  status: number
  title?: string
  detail?: string
}
