// 화면 표기용 순수 함수 — 컴포넌트와 분리해서 단위 테스트한다.

import type { Confidence, PositionStats, Trend, TypeBreakdown } from '../api/types'

/** 0..1 확률 → "95%" (반올림, 0~100 클램프). */
export function formatPercent(probability: number): string {
  const percent = Math.round(Math.min(1, Math.max(0, probability)) * 100)
  return `${percent}%`
}

/** F5 근거 표기: "최근 20회 중 19회 연주". */
export function evidenceText(playedCount: number, sampleSize: number): string {
  return `최근 ${sampleSize}회 중 ${playedCount}회 연주`
}

/** 평균 셋 내 위치 → "보통 3번째 곡". 계산 전이면 null. */
export function positionText(avgPosition: number | null): string | null {
  if (avgPosition == null) {
    return null
  }
  return `보통 ${Math.round(avgPosition)}번째 곡`
}

/** 앙코르 등장 비율이 절반 이상이면 앙코르 단골로 표기한다. */
export function isEncoreStaple(encoreRatio: number | null): boolean {
  return (encoreRatio ?? 0) >= 0.5
}

/** ISO 날짜 → "2026.10.02 (금)". */
export function formatEventDate(isoDate: string): string {
  const date = new Date(`${isoDate}T00:00:00`)
  const weekday = ['일', '월', '화', '수', '목', '금', '토'][date.getDay()]
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}.${pad(date.getMonth() + 1)}.${pad(date.getDate())} (${weekday})`
}

/**
 * 곡의 성격 라벨 — 근거 수치에서 해석을 만든다.
 * 고정곡(표본의 90%+ 연주) / 로테이션곡(절반 이하) / 오프너 단골(평균 3번째 이내) /
 * 앙코르 단골(isEncoreStaple). 서로 배타적이지 않다.
 */
export function songRoleLabels(input: {
  playedCount: number
  sampleSize: number
  avgPosition: number | null
  encoreRatio: number | null
}): string[] {
  const labels: string[] = []
  // 표본이 없으면 빈도 해석 자체가 성립하지 않는다 — 라벨을 만들지 않는다
  if (input.sampleSize > 0) {
    const frequency = input.playedCount / input.sampleSize
    if (frequency >= 0.9) {
      labels.push('고정곡')
    } else if (frequency <= 0.5) {
      labels.push('로테이션곡')
    }
  }
  if (input.avgPosition != null && input.avgPosition <= 3) {
    labels.push('오프너 단골')
  }
  if (isEncoreStaple(input.encoreRatio)) {
    labels.push('앙코르 단골')
  }
  return labels
}

/**
 * 예상 셋 규모 — 아티스트의 최근 공연 평균 곡 수를 우선 쓰고,
 * 통계가 없으면 확률 50% 이상인 곡 수(그마저 없으면 20)로 잡는다.
 */
export function expectedSetSize(
  avgSongCount: number | null | undefined,
  predictions: { probability: number }[],
): number {
  if (avgSongCount != null && avgSongCount > 0) {
    return Math.min(Math.round(avgSongCount), predictions.length)
  }
  const likely = predictions.filter((p) => p.probability >= 0.5).length
  return Math.min(likely > 0 ? likely : 20, predictions.length)
}

/**
 * 예상 셋리스트(타임라인 순서) — 확률 상위 size곡을 골라 평균 등장 위치순으로 배열한다.
 * "처음 곡부터 마지막 곡까지 순서대로 예습"하는 뷰의 근거. 위치 정보가 없는 곡은 맨 뒤,
 * 동률은 rank(확률순)로 결정적이게.
 */
export function buildExpectedSetlist<
  T extends { rank: number; probability: number; avgPosition: number | null },
>(predictionsByRank: T[], size: number): T[] {
  return predictionsByRank
    .slice(0, Math.max(1, size))
    .toSorted((a, b) => {
      const posA = a.avgPosition ?? Number.POSITIVE_INFINITY
      const posB = b.avgPosition ?? Number.POSITIVE_INFINITY
      return posA !== posB ? posA - posB : a.rank - b.rank
    })
}

/** 신뢰도 라벨 → 화면 문구 (E1). */
export function confidenceText(confidence: Confidence): string {
  return {
    VERY_HIGH: '신뢰도 매우 높음',
    HIGH: '신뢰도 높음',
    MEDIUM: '신뢰도 보통',
    LOW: '신뢰도 낮음',
  }[confidence]
}

/** 추이 배지 (E4) — STABLE·null은 배지를 만들지 않는다. */
export function trendBadge(trend: Trend | null): { arrow: string; label: string } | null {
  if (trend === 'RISING') {
    return { arrow: '↑', label: '최근 상승' }
  }
  if (trend === 'FALLING') {
    return { arrow: '↓', label: '최근 하락' }
  }
  return null
}

/**
 * 위치 구간 비율 (E3) — 분모는 곡이 등장한 공연 수. 등장 0회 구간은 표기에서 뺀다.
 * 반올림 백분율이라 합이 100이 아닐 수 있다(표기용).
 */
export function positionSegments(
  stats: PositionStats,
  playedCount: number,
): { label: string; percent: number }[] {
  if (playedCount <= 0) {
    return []
  }
  const entries: [string, number][] = [
    ['오프너', stats.opener],
    ['초반', stats.early],
    ['중반', stats.mid],
    ['후반', stats.late],
    ['앙코르', stats.encore],
  ]
  return entries
    .filter(([, count]) => count > 0)
    .map(([label, count]) => ({ label, percent: Math.round((count / playedCount) * 100) }))
}

/** 유형별 등장 표기 (E4) — 표본에 없는 유형(분모 0)은 생략, 둘 다 없으면 null. */
export function typeBreakdownText(breakdown: TypeBreakdown): string | null {
  const parts: string[] = []
  if (breakdown.festivalShows > 0) {
    parts.push(`페스티벌 ${breakdown.festivalShows}회 중 ${breakdown.festivalPlayed}회`)
  }
  if (breakdown.soloShows > 0) {
    parts.push(`단독 ${breakdown.soloShows}회 중 ${breakdown.soloPlayed}회`)
  }
  return parts.length > 0 ? parts.join(' · ') : null
}

/** 유형 부스트가 확률에 기여한 정도 → "+4%p" (E1). 반올림해 0이면 표기하지 않는다(null). */
export function boostEffectText(boostEffect: number | null): string | null {
  if (boostEffect == null) {
    return null
  }
  const points = Math.round(boostEffect * 100)
  if (points === 0) {
    return null
  }
  return `${points > 0 ? '+' : ''}${points}%p`
}

/** 공연까지 남은 날: "D-64" / "D-DAY" / 지났으면 "공연 종료". */
export function dDayText(isoDate: string, today: Date = new Date()): string {
  const event = new Date(`${isoDate}T00:00:00`)
  const base = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  const diffDays = Math.round((event.getTime() - base.getTime()) / 86_400_000)
  if (diffDays > 0) {
    return `D-${diffDays}`
  }
  return diffDays === 0 ? 'D-DAY' : '공연 종료'
}
