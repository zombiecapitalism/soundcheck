import { useState } from 'react'
import { useSimilarShows } from '../api/queries'
import { formatEventDate } from '../lib/format'

/**
 * 참고할 만한 최근 공연(E11) — 예측 대상과 가장 비슷한 과거 공연 3건.
 * 셋리스트는 접어두고 탭해서 펼친다(모바일 우선).
 */
export default function SimilarShowsSection({ eventId }: { eventId: number }) {
  const { data } = useSimilarShows(eventId)
  const [openId, setOpenId] = useState<string | null>(null)

  if (!data || data.shows.length === 0) {
    return null
  }

  return (
    <section className="similar-section">
      <h2>참고할 만한 최근 공연</h2>
      <ul className="similar-list">
        {data.shows.map((show) => (
          <li key={show.setlistId} className="similar-item">
            <button
              type="button"
              className="similar-toggle"
              aria-expanded={openId === show.setlistId}
              onClick={() => setOpenId(openId === show.setlistId ? null : show.setlistId)}
            >
              <span className="similar-top">
                <span className="similar-date">{formatEventDate(show.eventDate)}</span>
                {show.typeMatch && show.showType === 'FESTIVAL' && (
                  <span className="festival-tag">페스티벌</span>
                )}
              </span>
              <span className="similar-venue">
                {show.venueName ?? '공연장 미상'}
                {show.cityName && ` · ${show.cityName}`}
              </span>
              <span className="similar-meta">
                {show.setlist.length}곡 · 예측 상위권과 {show.overlapCount}곡 겹침
              </span>
            </button>
            {openId === show.setlistId && (
              <ol className="similar-setlist">
                {show.setlist.map((entry) => (
                  <li key={entry.position}>
                    {entry.songName}
                    {entry.encore && <span className="encore-badge">앙코르</span>}
                  </li>
                ))}
              </ol>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}
