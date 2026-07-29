import { Link, useParams } from 'react-router'
import { useArtist, useEvent, usePredictions } from '../api/queries'
import ProbabilityBar from '../components/ProbabilityBar'
import StatusView from '../components/StatusView'
import {
  evidenceText,
  formatEventDate,
  formatPercent,
  isEncoreStaple,
  positionText,
} from '../lib/format'

/** 예측 상세 — 곡별 확률 리스트. 각 항목에 F5 근거("최근 20회 중 19회 연주")를 함께 표기한다. */
export default function PredictionsPage() {
  const eventId = Number(useParams().eventId)
  const { data: event } = useEvent(eventId)
  const { data: artist } = useArtist(event?.artist.mbid)
  const { data: predictions, isPending, isError, error, refetch } = usePredictions(eventId)

  return (
    <>
      <Link to="/" className="back-link">
        ← 공연 목록
      </Link>

      {event && (
        <header className="event-header">
          <h1 className="page-title">{event.artist.name}</h1>
          <p className="event-header-meta">
            {event.eventName} · {formatEventDate(event.eventDate)}
            {event.venueName && <> · {event.venueName}</>}
          </p>
          {artist && artist.recentShows.total > 0 && (
            <p className="artist-stats">
              수집한 공연 {artist.recentShows.total}회
              {artist.recentShows.festival > 0 && <> · 페스티벌 {artist.recentShows.festival}회</>}
              {artist.recentShows.avgSongCount != null && (
                <> · 평균 {Math.round(artist.recentShows.avgSongCount)}곡</>
              )}
            </p>
          )}
        </header>
      )}

      {isPending && <StatusView kind="loading" message="예측을 불러오는 중…" />}
      {isError && <StatusView kind="error" message={error.message} onRetry={() => refetch()} />}
      {predictions && predictions.length === 0 && (
        <StatusView kind="empty" message="아직 예측이 계산되지 않았어요. 곧 준비됩니다." />
      )}

      {predictions && predictions.length > 0 && (
        <ol className="prediction-list">
          {predictions.map((prediction) => {
            const position = positionText(prediction.avgPosition)
            return (
              <li key={prediction.songKey}>
                <Link
                  to={`/events/${eventId}/songs/${encodeURIComponent(prediction.songKey)}`}
                  className="prediction-item"
                >
                  <div className="prediction-row">
                    <span className="prediction-rank">{prediction.rank}</span>
                    <span className="prediction-song">{prediction.songName}</span>
                    <span className="prediction-percent">
                      {formatPercent(prediction.probability)}
                    </span>
                  </div>
                  <ProbabilityBar probability={prediction.probability} />
                  <p className="prediction-evidence">
                    {evidenceText(prediction.playedCount, prediction.sampleSize)}
                    {position && <> · {position}</>}
                    {isEncoreStaple(prediction.encoreRatio) && (
                      <span className="encore-badge">앙코르 단골</span>
                    )}
                  </p>
                </Link>
              </li>
            )
          })}
        </ol>
      )}
    </>
  )
}
