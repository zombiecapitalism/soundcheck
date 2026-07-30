import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useAccuracy, useArtist, useEvent, usePredictions } from '../api/queries'
import ProbabilityBar from '../components/ProbabilityBar'
import StatusView from '../components/StatusView'
import { usePracticeChecklist } from '../hooks/usePracticeChecklist'
import { practiceProgress } from '../lib/practice'
import {
  buildExpectedSetlist,
  evidenceText,
  expectedSetSize,
  formatEventDate,
  formatPercent,
  isEncoreStaple,
  positionText,
} from '../lib/format'

/** 예측 상세 — 곡별 확률 리스트. 각 항목에 F5 근거("최근 20회 중 19회 연주")를 함께 표기한다. */
export default function PredictionsPage() {
  const eventId = Number(useParams().eventId)
  // 훅은 항상 같은 순서로 호출돼야 하므로 유효성과 무관하게 먼저 건다(무효면 enabled=false)
  const { data: event } = useEvent(eventId)
  const { data: artist } = useArtist(event?.artist.mbid)
  const { data: predictions, isPending, isError, error, refetch } = usePredictions(eventId)
  const { data: accuracy } = useAccuracy(eventId, event?.verified ?? false)
  const playedByKey = new Map(accuracy?.results.map((r) => [r.songKey, r.played]) ?? [])
  // 보기 전환: 확률순(기본) vs 예상 순서(처음 곡부터 순서대로 예습하는 뷰)
  const [view, setView] = useState<'probability' | 'timeline'>('probability')
  const setSize = expectedSetSize(artist?.recentShows.avgSongCount, predictions ?? [])
  const displayed =
    view === 'timeline' && predictions ? buildExpectedSetlist(predictions, setSize) : predictions
  // 예습 체크 — 기기 로컬 상태(localStorage)
  const { checkedKeys, toggle } = usePracticeChecklist(eventId)
  const progress = practiceProgress(predictions ?? [], checkedKeys, setSize)

  // id가 숫자가 아니면 쿼리가 시작되지 않아 isPending이 영원히 true다 — 로딩으로 위장되면 안 된다
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

      {accuracy && (
        <section className="accuracy-card">
          <div className="accuracy-headline">
            <span className="accuracy-percent">{formatPercent(accuracy.precisionAtK)}</span>
            <span className="accuracy-label">예측 적중률</span>
          </div>
          <p className="accuracy-detail">
            실제 {accuracy.actualSongCount}곡 공연 — 예측 상위 {accuracy.topK}곡 중{' '}
            {accuracy.topKHits}곡 적중
            {accuracy.surprises.length > 0 && <> · 예측 밖 {accuracy.surprises.length}곡</>}
          </p>
          {accuracy.surprises.length > 0 && (
            <p className="accuracy-surprises">
              놓친 곡: {accuracy.surprises.map((s) => s.songName).join(', ')}
            </p>
          )}
        </section>
      )}

      {isPending && <StatusView kind="loading" message="예측을 불러오는 중…" />}
      {isError && <StatusView kind="error" message={error.message} onRetry={() => refetch()} />}
      {predictions && predictions.length === 0 && (
        <StatusView kind="empty" message="아직 예측이 계산되지 않았어요. 곧 준비됩니다." />
      )}

      {predictions && predictions.length > 0 && (
        <>
          <div className="view-toggle" role="group" aria-label="보기 방식">
            <button
              type="button"
              className={view === 'probability' ? 'toggle-button active' : 'toggle-button'}
              onClick={() => setView('probability')}
            >
              확률순
            </button>
            <button
              type="button"
              className={view === 'timeline' ? 'toggle-button active' : 'toggle-button'}
              onClick={() => setView('timeline')}
            >
              예상 순서
            </button>
          </div>
          {view === 'timeline' && (
            <p className="view-hint">
              확률 상위 {setSize}곡을 평균 등장 위치순으로 배열 — 처음 곡부터 순서대로 예습하는 뷰
            </p>
          )}
          {progress.total > 0 && (
            <div className="practice-progress">
              <span className="practice-progress-text">
                예습 {progress.done}/{progress.total}곡
              </span>
              <div className="practice-progress-track" aria-hidden="true">
                <div
                  className="practice-progress-fill"
                  style={{ width: `${(progress.done / progress.total) * 100}%` }}
                />
              </div>
            </div>
          )}
          <ol className="prediction-list">
            {(displayed ?? []).map((prediction, index) => {
              const position = positionText(prediction.avgPosition)
              const played = playedByKey.get(prediction.songKey)
              const practiced = checkedKeys.has(prediction.songKey)
              return (
                <li key={prediction.songKey}>
                  <Link
                    to={`/events/${eventId}/songs/${encodeURIComponent(prediction.songKey)}`}
                    className={practiced ? 'prediction-item practiced' : 'prediction-item'}
                  >
                    <div className="prediction-row">
                      <span className="prediction-rank">
                        {view === 'timeline' ? index + 1 : prediction.rank}
                      </span>
                      <span className="prediction-song">
                        {prediction.songName}
                        {played === true && <span className="hit-badge">적중</span>}
                        {played === false && <span className="miss-badge">미연주</span>}
                      </span>
                      <span className="prediction-percent">
                        {formatPercent(prediction.probability)}
                      </span>
                      <button
                        type="button"
                        className={practiced ? 'practice-check checked' : 'practice-check'}
                        aria-label={practiced ? '예습 완료 해제' : '예습 완료 표시'}
                        aria-pressed={practiced}
                        onClick={(e) => {
                          // 행 전체가 곡 상세 링크라, 체크는 내비게이션을 막고 토글만 한다
                          e.preventDefault()
                          e.stopPropagation()
                          toggle(prediction.songKey)
                        }}
                      >
                        ✓
                      </button>
                    </div>
                    {view === 'probability' && <ProbabilityBar probability={prediction.probability} />}
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
        </>
      )}
    </>
  )
}
