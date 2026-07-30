import { Link } from 'react-router'
import { useAccuracyArchive, useEvents } from '../api/queries'
import StatusView from '../components/StatusView'
import { dDayText, formatEventDate, formatPercent } from '../lib/format'

/** 메인 — 다가오는 이벤트 목록 + 지난 공연 예측 성적 아카이브. */
export default function EventListPage() {
  const { data: events, isPending, isError, error, refetch } = useEvents()
  const { data: archive } = useAccuracyArchive()

  if (isPending) {
    return <StatusView kind="loading" message="공연 목록을 불러오는 중…" />
  }
  if (isError) {
    return <StatusView kind="error" message={error.message} onRetry={() => refetch()} />
  }

  // 검증된(적중률이 나온) 이벤트는 아래 아카이브 섹션에서 보여준다 — 목록 중복 방지
  const upcoming = events.filter((event) => !event.verified)

  return (
    <>
      <h1 className="page-title">다가오는 공연</h1>
      {upcoming.length === 0 && <StatusView kind="empty" message="등록된 공연이 아직 없어요." />}
      <ul className="event-list">
        {upcoming.map((event) => (
          <li key={event.id}>
            <Link to={`/events/${event.id}`} className="event-card">
              <div className="event-card-top">
                <span className="artist-name">{event.artist.name}</span>
                <span className="dday-badge">{dDayText(event.eventDate)}</span>
              </div>
              <div className="event-card-name">{event.eventName}</div>
              <div className="event-card-meta">
                {formatEventDate(event.eventDate)}
                {event.venueName && <> · {event.venueName}</>}
              </div>
            </Link>
          </li>
        ))}
      </ul>

      {archive && archive.length > 0 && (
        <section className="archive-section">
          <h2 className="archive-title">지난 공연 예측 성적</h2>
          <ul className="event-list">
            {archive.map((summary) => (
              <li key={summary.eventId}>
                <Link to={`/events/${summary.eventId}`} className="event-card archive-card">
                  <div className="event-card-top">
                    <span className="artist-name">{summary.artistName}</span>
                    <span className="archive-percent">{formatPercent(summary.precisionAtK)}</span>
                  </div>
                  <div className="event-card-name">{summary.eventName}</div>
                  <div className="event-card-meta">
                    {formatEventDate(summary.eventDate)} · 상위 {summary.topK}곡 중{' '}
                    {summary.topKHits}곡 적중
                  </div>
                  <div className="event-card-meta">
                    F1 {formatPercent(summary.f1)} · Top-5 {summary.top5Hits}/{summary.top5Size} ·
                    Top-10 {summary.top10Hits}/{summary.top10Size}
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  )
}
