import type { AccuracyReport, AccuracySummary, ArtistDetail, ArtistStats, EventSummary, ExpectedSetlist, PlaylistResult, Prediction, PredictionDetail, ProblemDetail, SimilarShows, SongStats } from './types'

/** 백엔드 에러(RFC 7807)를 메시지로 옮긴 예외. */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  })
  if (!response.ok) {
    let message = `요청 실패 (${response.status})`
    try {
      const problem = (await response.json()) as ProblemDetail
      message = problem.detail ?? problem.title ?? message
    } catch {
      // Problem 형식이 아니면 상태 코드 메시지 유지
    }
    throw new ApiError(response.status, message)
  }
  return response.json() as Promise<T>
}

export const api = {
  events: () => request<EventSummary[]>('/api/events'),
  predictions: (eventId: number) => request<Prediction[]>(`/api/events/${eventId}/predictions`),
  artist: (mbid: string) => request<ArtistDetail>(`/api/artists/${mbid}`),
  accuracy: (eventId: number) => request<AccuracyReport>(`/api/events/${eventId}/accuracy`),
  accuracyArchive: () => request<AccuracySummary[]>('/api/events/accuracy'),
  predictionDetail: (eventId: number, songKey: string) =>
    request<PredictionDetail>(`/api/events/${eventId}/predictions/${encodeURIComponent(songKey)}`),
  songStats: (mbid: string, songKey: string) =>
    request<SongStats>(`/api/artists/${mbid}/songs/${encodeURIComponent(songKey)}/stats`),
  artistStats: (mbid: string) => request<ArtistStats>(`/api/artists/${mbid}/stats`),
  expectedSetlist: (eventId: number) =>
    request<ExpectedSetlist>(`/api/events/${eventId}/expected-setlist`),
  similarShows: (eventId: number) => request<SimilarShows>(`/api/events/${eventId}/similar-shows`),
  /** POST인 이유: 캐시 미스 곡은 서버가 YouTube 검색(쿼터 소모)을 실행한다. */
  createPlaylist: (eventId: number, songKeys: string[]) =>
    request<PlaylistResult>(`/api/events/${eventId}/playlist`, {
      method: 'POST',
      body: JSON.stringify({ songKeys }),
    }),
}
