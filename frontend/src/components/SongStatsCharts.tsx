import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { SongStats } from '../api/types'
import { useSongStats } from '../api/queries'

/**
 * 곡 장기 통계 차트(E5) — 모바일 우선이라 2종만: 연도별 등장률 라인, 투어별 등장률 바.
 * 단일 시리즈(등장률)라 범례 없이 제목이 시리즈명을 겸한다. 색은 앱 액센트 하나만 쓴다.
 * recharts가 번들에서 가장 크므로 이 파일은 SongPage에서 lazy로 로드한다.
 */

const AXIS_TICK = { fill: 'var(--text-muted)', fontSize: 11 } as const
const GRID_STROKE = 'var(--border)'
const ACCENT = 'var(--accent)'
const TOOLTIP_STYLE = {
  background: 'var(--surface-raised)',
  border: '1px solid var(--border)',
  borderRadius: 8,
  fontSize: 12,
  color: 'var(--text)',
} as const

/** 표기용 등장률 %. 분모 0은 호출 전에 걸러진다(집계가 곡 있는 공연만 세므로 0이 오지 않는다). */
function rate(played: number, total: number): number {
  return Math.round((played / total) * 100)
}

function YearlyRateChart({ yearly }: { yearly: SongStats['yearly'] }) {
  // 한 해뿐이면 "추이"가 성립하지 않는다 — 차트를 그리지 않는다
  if (yearly.length < 2) {
    return null
  }
  const data = yearly.map((row) => ({
    year: String(row.year),
    rate: rate(row.playedShows, row.totalShows),
    detail: `${row.totalShows}회 중 ${row.playedShows}회`,
  }))
  return (
    <figure className="stats-figure">
      <figcaption>연도별 등장률</figcaption>
      <ResponsiveContainer width="100%" height={180}>
        <LineChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: -18 }}>
          <CartesianGrid stroke={GRID_STROKE} strokeDasharray="2 4" vertical={false} />
          <XAxis dataKey="year" tick={AXIS_TICK} axisLine={{ stroke: GRID_STROKE }} tickLine={false} />
          <YAxis
            domain={[0, 100]}
            ticks={[0, 50, 100]}
            tick={AXIS_TICK}
            axisLine={false}
            tickLine={false}
            unit="%"
          />
          <Tooltip
            contentStyle={TOOLTIP_STYLE}
            formatter={(value, _name, item) => [
              `${String(value)}% (${(item.payload as { detail: string }).detail})`,
              '등장률',
            ]}
            cursor={{ stroke: GRID_STROKE }}
          />
          <Line
            type="monotone"
            dataKey="rate"
            stroke={ACCENT}
            strokeWidth={2}
            dot={{ r: 4, fill: ACCENT, strokeWidth: 0 }}
            activeDot={{ r: 5 }}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </figure>
  )
}

const MAX_TOURS = 5

function TourRateChart({ tours }: { tours: SongStats['tours'] }) {
  // 이미 공연 수 내림차순 — 상위 N개 투어만 (표기 흔들림·롱테일 대응)
  const top = tours.slice(0, MAX_TOURS)
  if (top.length < 2) {
    return null
  }
  const data = top.map((row) => ({
    tour: row.tourName ?? '투어 없음',
    rate: rate(row.playedShows, row.totalShows),
    detail: `${row.totalShows}회 중 ${row.playedShows}회`,
  }))
  return (
    <figure className="stats-figure">
      <figcaption>투어별 등장률</figcaption>
      <ResponsiveContainer width="100%" height={data.length * 34 + 30}>
        <BarChart data={data} layout="vertical" margin={{ top: 0, right: 34, bottom: 0, left: 8 }}>
          <XAxis type="number" domain={[0, 100]} hide />
          <YAxis
            type="category"
            dataKey="tour"
            width={110}
            tick={AXIS_TICK}
            axisLine={false}
            tickLine={false}
          />
          <Tooltip
            contentStyle={TOOLTIP_STYLE}
            formatter={(value, _name, item) => [
              `${String(value)}% (${(item.payload as { detail: string }).detail})`,
              '등장률',
            ]}
            cursor={{ fill: 'var(--track)' }}
          />
          <Bar
            dataKey="rate"
            barSize={14}
            radius={[0, 4, 4, 0]}
            isAnimationActive={false}
            label={{ position: 'right', fill: 'var(--text-muted)', fontSize: 11, formatter: (v) => `${String(v)}%` }}
          >
            {data.map((row) => (
              <Cell key={row.tour} fill={ACCENT} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </figure>
  )
}

/**
 * 곡 장기 통계 섹션 — 예측 표본(최근 20회)이 아니라 수집된 전체 공연 기준.
 * 추이를 만들 만큼 데이터가 없으면(연도 1개, 투어 1묶음) 섹션 자체를 그리지 않는다.
 */
export default function SongStatsSection({
  artistMbid,
  songKey,
}: {
  artistMbid: string | undefined
  songKey: string
}) {
  const { data: stats } = useSongStats(artistMbid, songKey)
  if (!stats || (stats.yearly.length < 2 && stats.tours.length < 2)) {
    return null
  }
  return (
    <section className="stats-section">
      <h2>
        장기 통계
        <span className="timeline-count">수집한 전체 공연 기준</span>
      </h2>
      <YearlyRateChart yearly={stats.yearly} />
      <TourRateChart tours={stats.tours} />
    </section>
  )
}
