import { describe, expect, it } from 'vitest'
import {
  dDayText,
  evidenceText,
  formatEventDate,
  formatPercent,
  isEncoreStaple,
  positionText,
  songRoleLabels,
} from './format'

describe('formatPercent', () => {
  it('반올림해서 정수 퍼센트로 표기한다', () => {
    expect(formatPercent(0.95)).toBe('95%')
    expect(formatPercent(0.6667)).toBe('67%')
    expect(formatPercent(1)).toBe('100%')
    expect(formatPercent(0)).toBe('0%')
  })

  it('범위 밖 값은 클램프한다 — 확률이 100%를 넘게 보이면 안 된다', () => {
    expect(formatPercent(1.2)).toBe('100%')
    expect(formatPercent(-0.1)).toBe('0%')
  })
})

describe('evidenceText', () => {
  it('F5 근거 형식을 만든다', () => {
    expect(evidenceText(19, 20)).toBe('최근 20회 중 19회 연주')
  })
})

describe('positionText', () => {
  it('평균 위치를 반올림해 표기한다', () => {
    expect(positionText(2.5)).toBe('보통 3번째 곡')
    expect(positionText(1.2)).toBe('보통 1번째 곡')
  })

  it('계산 전(null)이면 null', () => {
    expect(positionText(null)).toBeNull()
  })
})

describe('isEncoreStaple', () => {
  it('앙코르 비율 절반 이상만 단골로 본다', () => {
    expect(isEncoreStaple(0.5)).toBe(true)
    expect(isEncoreStaple(0.49)).toBe(false)
    expect(isEncoreStaple(null)).toBe(false)
  })
})

describe('formatEventDate', () => {
  it('한국식 날짜 + 요일로 표기한다', () => {
    expect(formatEventDate('2026-10-02')).toBe('2026.10.02 (금)')
  })
})

describe('songRoleLabels', () => {
  const base = { playedCount: 10, sampleSize: 20, avgPosition: null, encoreRatio: null }

  it('표본의 90% 이상 연주면 고정곡', () => {
    expect(songRoleLabels({ ...base, playedCount: 18 })).toContain('고정곡')
    expect(songRoleLabels({ ...base, playedCount: 17 })).not.toContain('고정곡')
  })

  it('절반 이하 연주면 로테이션곡 — 고정곡과 배타적', () => {
    expect(songRoleLabels({ ...base, playedCount: 10 })).toContain('로테이션곡')
    expect(songRoleLabels({ ...base, playedCount: 11 })).toEqual([])
  })

  it('평균 3번째 이내면 오프너 단골', () => {
    expect(songRoleLabels({ ...base, playedCount: 11, avgPosition: 3 })).toContain('오프너 단골')
    expect(songRoleLabels({ ...base, playedCount: 11, avgPosition: 3.5 })).not.toContain('오프너 단골')
  })

  it('앙코르 비율 절반 이상이면 앙코르 단골 — 라벨은 중첩 가능', () => {
    const labels = songRoleLabels({ playedCount: 19, sampleSize: 20, avgPosition: 2, encoreRatio: 0.6 })
    expect(labels).toEqual(['고정곡', '오프너 단골', '앙코르 단골'])
  })

  it('표본 0이면 빈도 라벨을 만들지 않는다', () => {
    expect(songRoleLabels({ playedCount: 0, sampleSize: 0, avgPosition: null, encoreRatio: null }))
      .toEqual([])
  })
})

describe('dDayText', () => {
  const today = new Date(2026, 6, 30) // 2026-07-30

  it('남은 날짜를 D-n으로 표기한다', () => {
    expect(dDayText('2026-10-02', today)).toBe('D-64')
    expect(dDayText('2026-07-31', today)).toBe('D-1')
  })

  it('당일은 D-DAY, 지난 공연은 종료로 표기한다', () => {
    expect(dDayText('2026-07-30', today)).toBe('D-DAY')
    expect(dDayText('2026-07-29', today)).toBe('공연 종료')
  })
})
