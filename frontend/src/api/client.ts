import type { ArtistDetail, EventSummary, Prediction, ProblemDetail } from './types'

/** 백엔드 에러(RFC 7807)를 메시지로 옮긴 예외. */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: { Accept: 'application/json' } })
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
}
