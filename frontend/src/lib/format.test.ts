import { describe, expect, it } from 'vitest'
import {
  boostEffectText,
  confidenceText,
  dDayText,
  evidenceText,
  expectedSetSize,
  formatEventDate,
  formatPercent,
  isEncoreStaple,
  positionSegments,
  positionText,
  songRoleLabels,
  trendBadge,
  typeBreakdownText,
} from './format'
import { listenLinks } from './links'
import { practiceProgress, toggleKey } from './practice'

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

describe('expectedSetSize', () => {
  const predictions = Array.from({ length: 30 }, (_, i) => ({ probability: 1 - i * 0.03 }))

  it('아티스트 평균 곡 수를 반올림해 쓴다', () => {
    expect(expectedSetSize(14.4, predictions)).toBe(14)
    expect(expectedSetSize(14.5, predictions)).toBe(15)
  })

  it('평균이 예측 곡 수보다 크면 예측 곡 수로 자른다', () => {
    expect(expectedSetSize(50, predictions)).toBe(30)
  })

  it('통계가 없으면 확률 50% 이상 곡 수로 대신한다', () => {
    // 1 - i*0.03 >= 0.5 → i <= 16 → 17곡
    expect(expectedSetSize(null, predictions)).toBe(17)
  })

  it('그마저 없으면 20 (예측 수 상한)', () => {
    const low = [{ probability: 0.3 }, { probability: 0.2 }]
    expect(expectedSetSize(null, low)).toBe(2)
  })
})

describe('toggleKey', () => {
  it('없으면 넣고 있으면 뺀다 — 원본은 불변', () => {
    const original = ['a']
    expect(toggleKey(original, 'b')).toEqual(['a', 'b'])
    expect(toggleKey(['a', 'b'], 'a')).toEqual(['b'])
    expect(original).toEqual(['a'])
  })
})

describe('practiceProgress', () => {
  const predictions = [{ songKey: 'a' }, { songKey: 'b' }, { songKey: 'c' }]

  it('예상 셋 규모(rank 상위 N곡) 기준으로 센다', () => {
    // c는 상위 2곡 밖 — 체크돼 있어도 분자에 안 들어간다
    expect(practiceProgress(predictions, new Set(['a', 'c']), 2)).toEqual({ done: 1, total: 2 })
  })

  it('규모가 예측 수보다 크면 예측 수가 분모다', () => {
    expect(practiceProgress(predictions, new Set(['a']), 10)).toEqual({ done: 1, total: 3 })
  })
})

describe('listenLinks', () => {
  it('아티스트+곡명으로 검색 링크 3종을 만든다', () => {
    const links = listenLinks('Avenged Sevenfold', 'Bat Country')
    expect(links.map((l) => l.label)).toEqual(['YouTube 라이브', 'Spotify', 'YouTube Music'])
    expect(links[0].url).toBe(
      'https://www.youtube.com/results?search_query=Avenged%20Sevenfold%20Bat%20Country%20live',
    )
    expect(links[1].url).toContain('open.spotify.com/search/Avenged%20Sevenfold%20Bat%20Country')
  })

  it('특수문자를 URL 인코딩한다', () => {
    const [youtube] = listenLinks('羊文学', 'あいまいでいいよ&more')
    expect(youtube.url).not.toContain('&more')
    expect(youtube.url).toContain(encodeURIComponent('あいまいでいいよ&more'))
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

describe('confidenceText', () => {
  it('신뢰도 라벨을 한국어 문구로 바꾼다', () => {
    expect(confidenceText('VERY_HIGH')).toBe('신뢰도 매우 높음')
    expect(confidenceText('LOW')).toBe('신뢰도 낮음')
  })
})

describe('trendBadge', () => {
  it('상승·하락만 배지를 만들고 STABLE·null은 만들지 않는다', () => {
    expect(trendBadge('RISING')).toEqual({ arrow: '↑', label: '최근 상승' })
    expect(trendBadge('FALLING')).toEqual({ arrow: '↓', label: '최근 하락' })
    expect(trendBadge('STABLE')).toBeNull()
    expect(trendBadge(null)).toBeNull()
  })
})

describe('positionSegments', () => {
  it('등장한 구간만 백분율로 만든다 — 분모는 곡의 등장 공연 수', () => {
    const segments = positionSegments({ opener: 0, early: 1, mid: 2, late: 4, encore: 1 }, 8)
    expect(segments).toEqual([
      { label: '초반', percent: 13 },
      { label: '중반', percent: 25 },
      { label: '후반', percent: 50 },
      { label: '앙코르', percent: 13 },
    ])
  })

  it('등장 0회면 빈 배열 — 0으로 나누지 않는다', () => {
    expect(positionSegments({ opener: 0, early: 0, mid: 0, late: 0, encore: 0 }, 0)).toEqual([])
  })
})

describe('typeBreakdownText', () => {
  it('표본에 있는 유형만 표기한다', () => {
    expect(
      typeBreakdownText({ festivalShows: 12, festivalPlayed: 9, soloShows: 5, soloPlayed: 2 }),
    ).toBe('페스티벌 12회 중 9회 · 단독 5회 중 2회')
    expect(
      typeBreakdownText({ festivalShows: 12, festivalPlayed: 9, soloShows: 0, soloPlayed: 0 }),
    ).toBe('페스티벌 12회 중 9회')
  })

  it('전부 UNKNOWN 표본이면 null — 섹션 자체를 그리지 않는다', () => {
    expect(
      typeBreakdownText({ festivalShows: 0, festivalPlayed: 0, soloShows: 0, soloPlayed: 0 }),
    ).toBeNull()
  })
})

describe('boostEffectText', () => {
  it('퍼센트포인트로 부호와 함께 표기한다', () => {
    expect(boostEffectText(0.042)).toBe('+4%p')
    expect(boostEffectText(-0.03)).toBe('-3%p')
  })

  it('효과가 없거나(반올림 0) 데이터가 없으면 null', () => {
    expect(boostEffectText(0.001)).toBeNull()
    expect(boostEffectText(null)).toBeNull()
  })
})

describe('예습 코스 (E7)', async () => {
  const { COURSES, buildCourse, courseReason, courseSize, courseTier } = await import('./practice')

  const prediction = (
    rank: number,
    probability: number,
    extra: Partial<import('../api/types').Prediction> = {},
  ): import('../api/types').Prediction => ({
    rank,
    songKey: `song-${rank}`,
    songName: `Song ${rank}`,
    probability,
    playedCount: Math.round(probability * 20),
    sampleSize: 20,
    avgPosition: null,
    encoreRatio: null,
    recentCount5: null,
    trend: null,
    ...extra,
  })

  it('코스 시간 → 곡 수: 곡당 4.5분 버림, 최소 1곡', () => {
    expect(courseSize(30)).toBe(6)
    expect(courseSize(60)).toBe(13)
    expect(courseSize(120)).toBe(26)
    expect(courseSize(3)).toBe(1)
    expect(COURSES.map((c) => c.minutes)).toEqual([30, 60, 120])
  })

  it('확률 구간 → 필수/추천/심화 (경계 포함)', () => {
    expect(courseTier(0.8)).toBe('ESSENTIAL')
    expect(courseTier(0.79)).toBe('RECOMMENDED')
    expect(courseTier(0.5)).toBe('RECOMMENDED')
    expect(courseTier(0.49)).toBe('DEEP')
  })

  it('필수 → 추천 → 심화 순으로 채우고, 심화는 앙코르·오프너 단골 우선', () => {
    const predictions = [
      prediction(1, 0.9), // 필수
      prediction(2, 0.6), // 추천
      prediction(3, 0.3), // 심화 (특징 없음)
      prediction(4, 0.3, { encoreRatio: 0.7 }), // 심화 — 앙코르 단골이 먼저
      prediction(5, 0.3, { avgPosition: 2 }), // 심화 — 오프너 단골이 그다음
    ]
    const course = buildCourse(predictions, 30) // 6곡 > 5곡 → 전부
    expect(course.map((s) => s.prediction.rank)).toEqual([1, 2, 4, 5, 3])
    expect(course[0].tier).toBe('ESSENTIAL')
    expect(course[2].tier).toBe('DEEP')
  })

  it('곡 수 상한을 지킨다 — 30분 코스는 6곡', () => {
    const predictions = Array.from({ length: 10 }, (_, i) => prediction(i + 1, 0.9))
    expect(buildCourse(predictions, 30)).toHaveLength(6)
  })

  it('추천 이유는 근거 수치에서 나온다 — 고정곡 > 앙코르 > 오프너 > 상승세 > 폴백', () => {
    expect(courseReason(prediction(1, 0.95, { playedCount: 19 }))).toBe(
      '최근 20회 중 19회 연주된 고정곡',
    )
    expect(courseReason(prediction(1, 0.6, { playedCount: 12, encoreRatio: 0.6 }))).toContain(
      '앙코르 단골',
    )
    expect(courseReason(prediction(1, 0.6, { playedCount: 12, avgPosition: 2 }))).toContain(
      '오프너 단골',
    )
    expect(
      courseReason(prediction(1, 0.6, { playedCount: 12, trend: 'RISING', recentCount5: 4 })),
    ).toContain('상승세')
    expect(courseReason(prediction(1, 0.6, { playedCount: 12 }))).toBe('최근 20회 중 12회 연주')
  })
})
