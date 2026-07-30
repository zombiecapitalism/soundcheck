import { Link, useParams } from 'react-router'
import { usePredictionDetail } from '../api/queries'
import StatusView from '../components/StatusView'
import {
  evidenceText,
  formatEventDate,
  formatPercent,
  positionText,
  songRoleLabels,
} from '../lib/format'

/** 곡 상세 — 예측 근거를 최근 공연 타임라인으로 풀어 보여준다. RAG(F4)는 아래 플레이스홀더에 연결 예정. */
export default function SongPage() {
  const params = useParams()
  const eventId = Number(params.eventId)
  // react-router가 params를 이미 디코드해서 준다
  const songKey = params.songKey ?? ''
  const { data, isPending, isError, error, refetch } = usePredictionDetail(eventId, songKey)

  // id가 숫자가 아니면 쿼리가 시작되지 않아 isPending이 영원히 true다
  if (!Number.isFinite(eventId)) {
    return (
      <>
        <Link to="/" className="back-link">
          ← 공연 목록
        </Link>
        <StatusView kind="empty" message="주소가 잘못됐어요. 공연 목록에서 다시 선택해 주세요." />
      </>
    )
  }

  if (isPending) {
    return <StatusView kind="loading" message="곡 정보를 불러오는 중…" />
  }
  if (isError) {
    return (
      <>
        <Link to={`/events/${eventId}`} className="back-link">
          ← 예측 목록
        </Link>
        <StatusView kind="error" message={error.message} onRetry={() => refetch()} />
      </>
    )
  }

  const { prediction, history } = data
  const position = positionText(prediction.avgPosition)
  const labels = songRoleLabels(prediction)
  const playedCount = history.filter((entry) => entry.played).length

  return (
    <>
      <Link to={`/events/${eventId}`} className="back-link">
        ← 예측 목록
      </Link>
      <header className="song-header">
        <h1 className="page-title">{prediction.songName}</h1>
        <p className="song-summary">
          연주 확률 <strong>{formatPercent(prediction.probability)}</strong> ·{' '}
          {evidenceText(prediction.playedCount, prediction.sampleSize)}
          {position && <> · {position}</>}
        </p>
        {labels.length > 0 && (
          <p className="song-labels">
            {labels.map((label) => (
              <span key={label} className="role-badge">
                {label}
              </span>
            ))}
          </p>
        )}
      </header>

      <section className="timeline-section">
        <h2>
          최근 공연 타임라인
          <span className="timeline-count">
            {playedCount}/{history.length}회 연주
          </span>
        </h2>
        {/* 한눈에 보는 연주 밀도 — 최근(왼쪽)부터 */}
        <div className="play-strip" aria-hidden="true">
          {history.map((entry) => (
            <span
              key={entry.setlistId}
              className={entry.played ? 'strip-cell played' : 'strip-cell'}
            />
          ))}
        </div>
        <ol className="timeline-list">
          {history.map((entry) => (
            <li
              key={entry.setlistId}
              className={entry.played ? 'timeline-item played' : 'timeline-item'}
            >
              <span className="timeline-dot" aria-hidden="true" />
              <div className="timeline-body">
                <div className="timeline-top">
                  <span className="timeline-date">{formatEventDate(entry.eventDate)}</span>
                  {entry.showType === 'FESTIVAL' && <span className="festival-tag">페스티벌</span>}
                </div>
                <div className="timeline-venue">
                  {entry.venueName ?? '공연장 미상'}
                  {entry.cityName && ` · ${entry.cityName}`}
                </div>
                <div className="timeline-result">
                  {entry.played ? (
                    <>
                      {entry.playedSongCount}곡 중 {entry.position}번째
                      {entry.encore && <span className="encore-badge">앙코르</span>}
                    </>
                  ) : (
                    <span className="timeline-miss">미연주</span>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ol>
      </section>

      <section className="song-story-placeholder">
        <h2>곡 이야기</h2>
        <p>
          곡의 의미, 앨범 맥락, 라이브에서의 특징을 준비하고 있어요.
          <br />
          배경 설명이 연결되면 여기에 출처와 함께 표시됩니다.
        </p>
      </section>
    </>
  )
}
