// 예습 체크리스트·코스 추천(E7) — 순수 로직. 저장은 훅(usePracticeChecklist)이 localStorage로 한다.

import type { Prediction } from '../api/types'
import { isEncoreStaple } from './format'

/** 체크 토글 — 있으면 빼고 없으면 넣는다. 원본을 바꾸지 않는다. */
export function toggleKey(keys: readonly string[], key: string): string[] {
  return keys.includes(key) ? keys.filter((k) => k !== key) : [...keys, key]
}

/**
 * 예습 진행률 — 예상 셋 규모(확률 상위 setSize곡) 기준으로 센다.
 * 보기(확률순/예상 순서)를 바꿔도 분모가 흔들리지 않게 rank 상위 N곡으로 고정.
 */
export function practiceProgress(
  predictionsByRank: readonly { songKey: string }[],
  checkedKeys: ReadonlySet<string>,
  setSize: number,
): { done: number; total: number } {
  const target = predictionsByRank.slice(0, Math.max(0, setSize))
  return {
    done: target.filter((p) => checkedKeys.has(p.songKey)).length,
    total: target.length,
  }
}

// ---- 예습 코스 추천 (E7) ----
// 곡 길이 데이터가 없으므로 v1은 곡당 평균 4.5분 가정. LLM 이유 생성은 쓰지 않는다 —
// 규칙 문구가 근거 추적 가능하고 비용이 0이다.

/** 곡당 평균 재생 시간(분) 가정 — 30분 코스 ≈ 6곡, 1시간 ≈ 13곡, 2시간 ≈ 26곡. */
export const MINUTES_PER_SONG = 4.5

export const COURSES = [
  { id: '30m', label: '30분', minutes: 30 },
  { id: '1h', label: '1시간', minutes: 60 },
  { id: '2h', label: '2시간', minutes: 120 },
] as const

export type CourseId = (typeof COURSES)[number]['id']

export type CourseTier = 'ESSENTIAL' | 'RECOMMENDED' | 'DEEP'

export interface CourseSong {
  prediction: Prediction
  tier: CourseTier
  /** 규칙 기반 추천 이유 — E1 근거 데이터 재사용, 근거 추적 가능한 문구만 */
  reason: string
}

/** 확률 구간 → 코스 구분: 필수(≥ 0.8) / 추천(0.5~0.8) / 심화(< 0.5). */
export function courseTier(probability: number): CourseTier {
  if (probability >= 0.8) {
    return 'ESSENTIAL'
  }
  if (probability >= 0.5) {
    return 'RECOMMENDED'
  }
  return 'DEEP'
}

/** 코스 시간 → 곡 수 (버림 — "그 시간 안에 다 듣는" 보장). 최소 1곡. */
export function courseSize(minutes: number): number {
  return Math.max(1, Math.floor(minutes / MINUTES_PER_SONG))
}

/** 규칙 기반 추천 이유 — 근거 수치에서 문구를 만든다. 항상 하나는 나온다(등장 횟수 폴백). */
export function courseReason(prediction: Prediction): string {
  const frequency = prediction.sampleSize > 0 ? prediction.playedCount / prediction.sampleSize : 0
  if (frequency >= 0.9) {
    return `최근 ${prediction.sampleSize}회 중 ${prediction.playedCount}회 연주된 고정곡`
  }
  if (isEncoreStaple(prediction.encoreRatio)) {
    return '앙코르 단골 — 마지막을 함께 부르려면 예습 필수'
  }
  if (prediction.avgPosition != null && prediction.avgPosition <= 3) {
    return '오프너 단골 — 시작부터 아는 곡으로'
  }
  if (prediction.trend === 'RISING') {
    return `최근 상승세 — 최근 5회 중 ${prediction.recentCount5 ?? '?'}회 연주`
  }
  return `최근 ${prediction.sampleSize}회 중 ${prediction.playedCount}회 연주`
}

/**
 * 코스 구성 — 필수(확률순) → 추천(확률순) → 심화(앙코르·오프너 단골 우선) 순으로
 * 곡 수를 채운다. 심화 우선순위: 앙코르 단골 > 오프너 단골 > 확률순(rank).
 */
export function buildCourse(predictionsByRank: readonly Prediction[], minutes: number): CourseSong[] {
  const size = Math.min(courseSize(minutes), predictionsByRank.length)
  const essential = predictionsByRank.filter((p) => courseTier(p.probability) === 'ESSENTIAL')
  const recommended = predictionsByRank.filter((p) => courseTier(p.probability) === 'RECOMMENDED')
  const deep = predictionsByRank
    .filter((p) => courseTier(p.probability) === 'DEEP')
    .toSorted((a, b) => {
      const encoreDiff = Number(isEncoreStaple(b.encoreRatio)) - Number(isEncoreStaple(a.encoreRatio))
      if (encoreDiff !== 0) {
        return encoreDiff
      }
      const openerA = a.avgPosition != null && a.avgPosition <= 3
      const openerB = b.avgPosition != null && b.avgPosition <= 3
      if (openerA !== openerB) {
        return Number(openerB) - Number(openerA)
      }
      return a.rank - b.rank
    })
  return [...essential, ...recommended, ...deep].slice(0, size).map((prediction) => ({
    prediction,
    tier: courseTier(prediction.probability),
    reason: courseReason(prediction),
  }))
}
