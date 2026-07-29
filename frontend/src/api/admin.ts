import { ApiError } from './client'
import type { ShowType } from './types'

// 관리자 API — Basic 인증. 자격증명은 세션 동안만 브라우저에 둔다(sessionStorage).

const AUTH_KEY = 'encore-admin-auth'

export function saveAdminAuth(username: string, password: string) {
  sessionStorage.setItem(AUTH_KEY, btoa(`${username}:${password}`))
}

export function clearAdminAuth() {
  sessionStorage.removeItem(AUTH_KEY)
}

export function hasAdminAuth(): boolean {
  return sessionStorage.getItem(AUTH_KEY) != null
}

async function adminRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const auth = sessionStorage.getItem(AUTH_KEY)
  const response = await fetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...(auth ? { Authorization: `Basic ${auth}` } : {}),
      ...init?.headers,
    },
  })
  if (!response.ok) {
    let message = `요청 실패 (${response.status})`
    try {
      const problem = (await response.json()) as { detail?: string; title?: string }
      message = problem.detail ?? problem.title ?? message
    } catch {
      if (response.status === 401) {
        message = '인증에 실패했어요. 비밀번호를 확인해 주세요.'
      }
    }
    throw new ApiError(response.status, message)
  }
  return response.json() as Promise<T>
}

export interface ArtistCandidate {
  mbid: string
  name: string
  sortName: string | null
  disambiguation: string | null
  url: string | null
  alreadyRegistered: boolean
}

export interface RegisteredArtist {
  mbid: string
  name: string
  target: boolean
}

export interface BatchLogEntry {
  id: number
  jobType: string
  status: 'SUCCESS' | 'PARTIAL' | 'FAILED'
  artistMbid: string | null
  fetched: number
  updated: number
  skipped: number
  errorMessage: string | null
  startedAt: string
  finishedAt: string | null
}

export interface LogsResponse {
  collecting: boolean
  logs: BatchLogEntry[]
}

export interface CreateEventRequest {
  artistMbid: string
  eventName: string
  eventDate: string
  venueName: string | null
  expectedShowType: ShowType
}

export const adminApi = {
  logs: () => adminRequest<LogsResponse>('/api/admin/logs'),
  searchArtists: (name: string) =>
    adminRequest<ArtistCandidate[]>(`/api/admin/artists/search?name=${encodeURIComponent(name)}`),
  registeredArtists: () => adminRequest<RegisteredArtist[]>('/api/admin/artists'),
  registerArtist: (candidate: ArtistCandidate) =>
    adminRequest<RegisteredArtist>('/api/admin/artists', {
      method: 'POST',
      body: JSON.stringify({
        mbid: candidate.mbid,
        name: candidate.name,
        sortName: candidate.sortName,
        setlistFmUrl: candidate.url,
      }),
    }),
  createEvent: (request: CreateEventRequest) =>
    adminRequest<{ id: number; predictionStatus: string }>('/api/admin/events', {
      method: 'POST',
      body: JSON.stringify(request),
    }),
  startCollect: () => adminRequest<{ started: boolean }>('/api/admin/batch/collect', { method: 'POST' }),
  runPredict: () => adminRequest<BatchLogEntry[]>('/api/admin/batch/predict', { method: 'POST' }),
}
