// 화면 표기용 순수 함수 — 컴포넌트와 분리해서 단위 테스트한다.

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
