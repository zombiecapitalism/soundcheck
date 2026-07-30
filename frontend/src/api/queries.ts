import { useQuery } from '@tanstack/react-query'
import { api } from './client'

/** 서버 상태는 전부 TanStack Query가 소유한다. 별도 상태 관리 라이브러리는 두지 않는다. */

export function useEvents() {
  return useQuery({ queryKey: ['events'], queryFn: api.events })
}

/** 이벤트 단건은 목록 캐시에서 고른다 — 단건 API가 없어도 화면에는 충분하다. */
export function useEvent(eventId: number) {
  return useQuery({
    queryKey: ['events'],
    queryFn: api.events,
    select: (events) => events.find((event) => event.id === eventId),
  })
}

export function usePredictions(eventId: number) {
  return useQuery({
    queryKey: ['events', eventId, 'predictions'],
    queryFn: () => api.predictions(eventId),
    enabled: Number.isFinite(eventId),
  })
}

export function useArtist(mbid: string | undefined) {
  return useQuery({
    queryKey: ['artists', mbid],
    queryFn: () => api.artist(mbid!),
    enabled: !!mbid,
  })
}

/** 적중률 — 실제 셋리스트가 연결된(verified) 이벤트에서만 호출한다. */
export function useAccuracy(eventId: number, verified: boolean) {
  return useQuery({
    queryKey: ['events', eventId, 'accuracy'],
    queryFn: () => api.accuracy(eventId),
    enabled: Number.isFinite(eventId) && verified,
  })
}

/** 곡 상세 — 예측 근거 + 최근 공연 타임라인. */
export function usePredictionDetail(eventId: number, songKey: string) {
  return useQuery({
    queryKey: ['events', eventId, 'predictions', songKey],
    queryFn: () => api.predictionDetail(eventId, songKey),
    enabled: Number.isFinite(eventId) && songKey.length > 0,
  })
}

/** 적중률 아카이브 — 지난 공연들의 예측 성적. */
export function useAccuracyArchive() {
  return useQuery({ queryKey: ['accuracy-archive'], queryFn: api.accuracyArchive })
}

/** 예상 셋리스트(E6) — "예상 순서" 보기 전환 시에만 조회한다. */
export function useExpectedSetlist(eventId: number, enabled: boolean) {
  return useQuery({
    queryKey: ['events', eventId, 'expected-setlist'],
    queryFn: () => api.expectedSetlist(eventId),
    enabled: Number.isFinite(eventId) && enabled,
  })
}

/** 유사 공연(E11) — 참고할 만한 최근 공연 카드. */
export function useSimilarShows(eventId: number) {
  return useQuery({
    queryKey: ['events', eventId, 'similar-shows'],
    queryFn: () => api.similarShows(eventId),
    enabled: Number.isFinite(eventId),
  })
}

/** 곡 장기 통계(E5) — 곡 상세 차트용. 404(연주 기록 없음)는 화면에서 섹션 생략. */
export function useSongStats(mbid: string | undefined, songKey: string) {
  return useQuery({
    queryKey: ['artists', mbid, 'songs', songKey, 'stats'],
    queryFn: () => api.songStats(mbid!, songKey),
    enabled: !!mbid && songKey.length > 0,
    retry: false,
  })
}
