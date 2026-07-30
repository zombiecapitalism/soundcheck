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

/** 본문 없는 응답(DELETE 등) — response.json()을 호출하면 안 된다. */
async function adminRequestVoid(path: string, init?: RequestInit): Promise<void> {
  const auth = sessionStorage.getItem(AUTH_KEY)
  const response = await fetch(path, {
    ...init,
    headers: {
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
      /* Problem 형식이 아니면 기본 메시지 */
    }
    throw new ApiError(response.status, message)
  }
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
  ragIngesting: boolean
  logs: BatchLogEntry[]
}

export interface CreateEventRequest {
  artistMbid: string
  eventName: string
  eventDate: string
  venueName: string | null
  expectedShowType: ShowType
}

/** 수집 데이터에서 감지한 KR 미래 공연 — 수집 대상 아티스트의 내한만 잡힌다. */
export interface KoreaShow {
  setlistId: string
  artistMbid: string
  artistName: string
  eventDate: string
  venueName: string | null
  cityName: string | null
  showType: ShowType
  alreadyRegistered: boolean
}

/** RAG 저장소 상태(E10) — 수집 대상 아티스트별. */
export interface ArtistRagStatus {
  artistMbid: string
  artistName: string
  documentCount: number
  chunkCount: number
  explanationCount: number
  lastEmbedAt: string | null
  lastEmbedStatus: 'SUCCESS' | 'PARTIAL' | 'FAILED' | null
}

export interface RagDocumentSummary {
  id: number
  title: string
  sourceUrl: string
  docType: 'SONG' | 'ALBUM' | 'ARTIST'
  songKey: string | null
  chunkCount: number
  collectedAt: string
}

/** AI 사용량 대시보드(E9) — 오늘(KST) 기준. */
export interface AiDashboard {
  totalCalls: number
  cacheHitRate: number
  inputTokens: number
  outputTokens: number
  embeddingTokens: number
  estimatedCostUsd: number
  byType: {
    callType: string
    calls: number
    avgLatencyMs: number | null
    inputTokens: number
    outputTokens: number
    cacheHits: number
    /** 클라이언트가 스트림을 끊은 호출 — 비용은 발생, 오류 아님 */
    cancelled: number
    errors: number
  }[]
}

export const adminApi = {
  logs: () => adminRequest<LogsResponse>('/api/admin/logs'),
  koreaShows: () => adminRequest<KoreaShow[]>('/api/admin/korea-shows'),
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
  startRagIngest: () =>
    adminRequest<{ started: boolean }>('/api/admin/batch/rag-ingest', { method: 'POST' }),
  aiDashboard: () => adminRequest<AiDashboard>('/api/admin/ai-dashboard'),
  ragStatus: () => adminRequest<ArtistRagStatus[]>('/api/admin/rag/status'),
  ragDocuments: (artistMbid: string) =>
    adminRequest<RagDocumentSummary[]>(`/api/admin/rag/documents?artistMbid=${artistMbid}`),
  deleteRagDocument: (id: number) =>
    adminRequestVoid(`/api/admin/rag/documents/${id}`, { method: 'DELETE' }),
  evictExplanationCache: (artistMbid: string) =>
    adminRequestVoid(`/api/admin/rag/cache/${artistMbid}`, { method: 'DELETE' }),
}
