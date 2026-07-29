import { Link } from 'react-router'
import { useEvents } from '../api/queries'
import StatusView from '../components/StatusView'
import { dDayText, formatEventDate } from '../lib/format'

/** 메인 — 예측 대상 이벤트(밴드) 목록. */
export default function EventListPage() {
  const { data: events, isPending, isError, error, refetch } = useEvents()

  if (isPending) {
    return <StatusView kind="loading" message="공연 목록을 불러오는 중…" />
  }
  if (isError) {
    return <StatusView kind="error" message={error.message} onRetry={() => refetch()} />
  }
  if (events.length === 0) {
    return <StatusView kind="empty" message="등록된 공연이 아직 없어요." />
  }

  return (
    <>
      <h1 className="page-title">다가오는 공연</h1>
      <ul className="event-list">
        {events.map((event) => (
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
    </>
  )
}
