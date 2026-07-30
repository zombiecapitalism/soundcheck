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
  artist: {
    mbid: string
    name: string
  }
}

export interface Prediction {
  rank: number
  songKey: string
  songName: string
  probability: number // 0..1
  playedCount: number
  sampleSize: number
  avgPosition: number | null
  encoreRatio: number | null
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

/** 곡 상세 — 예측 근거 + 최근 공연 타임라인(연주/미연주 포함, 최근순). */
export interface PredictionDetail {
  prediction: Prediction
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
  }[]
}

/** 공연 후 예측 vs 실제 비교. precisionAtK가 헤드라인: 상위 K곡 예습 시 적중 비율. */
export interface AccuracyReport {
  actualSongCount: number
  topK: number
  topKHits: number
  precisionAtK: number
  totalHits: number
  recall: number
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

/** RFC 7807 Problem Detail — 에러 응답 공통 형식. */
export interface ProblemDetail {
  status: number
  title?: string
  detail?: string
}
