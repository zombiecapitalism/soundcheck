import { Fragment, useState } from 'react'
import { Link, useParams } from 'react-router'
import { useAccuracy, useArtist, useEvent, useExpectedSetlist, usePredictions } from '../api/queries'
import ChatSection from '../components/ChatSection'
import ProbabilityBar from '../components/ProbabilityBar'
import SimilarShowsSection from '../components/SimilarShowsSection'
import StatusView from '../components/StatusView'
import { usePracticeChecklist } from '../hooks/usePracticeChecklist'
import { COURSES, buildCourse, practiceProgress, type CourseId, type CourseTier } from '../lib/practice'
import {
  evidenceText,
  expectedSetSize,
  formatEventDate,
  formatPercent,
  isEncoreStaple,
  positionText,
  trendBadge,
} from '../lib/format'

const TIER_LABELS: Record<CourseTier, string> = {
  ESSENTIAL: '필수',
  RECOMMENDED: '추천',
  DEEP: '심화',
}

/** 예측 상세 — 곡별 확률 리스트. 각 항목에 F5 근거("최근 20회 중 19회 연주")를 함께 표기한다. */
export default function PredictionsPage() {
  const eventId = Number(useParams().eventId)
  // 훅은 항상 같은 순서로 호출돼야 하므로 유효성과 무관하게 먼저 건다(무효면 enabled=false)
  const { data: event } = useEvent(eventId)
  const { data: artist } = useArtist(event?.artist.mbid)
  const { data: predictions, isPending, isError, error, refetch } = usePredictions(eventId)
  const { data: accuracy } = useAccuracy(eventId, event?.verified ?? false)
  const playedByKey = new Map(accuracy?.results.map((r) => [r.songKey, r.played]) ?? [])
  // 보기 전환: 확률순(기본) / 예상 순서(E6) / 예습 코스(E7)
  const [view, setView] = useState<'probability' | 'timeline' | 'course'>('probability')
  const [courseId, setCourseId] = useState<CourseId>('1h')
  const { data: expected } = useExpectedSetlist(eventId, view === 'timeline')
  const setSize = expectedSetSize(artist?.recentShows.avgSongCount, predictions ?? [])
  const predictionsByKey = new Map(predictions?.map((p) => [p.songKey, p]) ?? [])
  // 예상 순서 뷰: 백엔드 블록 순서대로 예측 행을 배열. 앙코르 시작 인덱스에 구분선을 넣는다.
  // 두 쿼리(expected/predictions) 스냅샷이 어긋나 곡이 빠질 수 있으므로 블록별로 필터한 뒤
  // 경계를 계산해야 구분선이 밀리지 않는다
  const toRows = (items: { songKey: string }[]) =>
    items
      .map((item) => predictionsByKey.get(item.songKey))
      .filter((p): p is NonNullable<typeof p> => p != null)
  const mainRows = expected ? toRows(expected.main) : undefined
  const encoreRows = expected ? toRows(expected.encore) : undefined
  const timelineRows = mainRows && encoreRows ? [...mainRows, ...encoreRows] : undefined
  const encoreStartIndex = encoreRows && encoreRows.length > 0 ? mainRows!.length : null
  // 예습 코스 — 필수/추천/심화 구분과 규칙 기반 추천 이유(E7)
  const course = COURSES.find((c) => c.id === courseId) ?? COURSES[1]
  const courseSongs = view === 'course' && predictions ? buildCourse(predictions, course.minutes) : undefined
  const courseByKey = new Map(courseSongs?.map((song) => [song.prediction.songKey, song]) ?? [])
  const displayed =
    view === 'timeline' ? timelineRows
    : view === 'course' ? courseSongs?.map((song) => song.prediction)
    : predictions
  // 예습 체크 — 기기 로컬 상태(localStorage). 코스 뷰에서는 코스 곡이 분모다
  const { checkedKeys, toggle } = usePracticeChecklist(eventId)
  const progress = view === 'course' && courseSongs
    ? practiceProgress(courseSongs.map((song) => song.prediction), checkedKeys, courseSongs.length)
    : practiceProgress(predictions ?? [], checkedKeys, setSize)

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
          {event.trendSummary && (
            <p className="trend-summary">
              <span className="trend-summary-label">최근 변화</span> {event.trendSummary}
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
          <p className="accuracy-detail">
            F1 {formatPercent(accuracy.f1)} · Top-5 {accuracy.top5.hits}/{accuracy.top5.size} ·
            Top-10 {accuracy.top10.hits}/{accuracy.top10.size}
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
            <button
              type="button"
              className={view === 'course' ? 'toggle-button active' : 'toggle-button'}
              onClick={() => setView('course')}
            >
              예습 코스
            </button>
          </div>
          {view === 'course' && (
            <div className="course-picker" role="group" aria-label="예습 시간">
              {COURSES.map((option) => (
                <button
                  key={option.id}
                  type="button"
                  className={courseId === option.id ? 'course-chip active' : 'course-chip'}
                  onClick={() => setCourseId(option.id)}
                >
                  {option.label}
                </button>
              ))}
              {courseSongs && (
                <span className="course-hint">
                  곡당 4.5분 가정 · {courseSongs.length}곡
                </span>
              )}
            </div>
          )}
          {view === 'timeline' && expected && (
            <p className="view-hint">
              {event?.expectedShowType === 'FESTIVAL' ? '페스티벌' : '단독'} 평균{' '}
              {expected.expectedSongCount}곡 기준 — 오프너·본편(평균 위치순)·앙코르 블록 구성
            </p>
          )}
          {view === 'timeline' && !expected && (
            <p className="view-hint">예상 순서를 구성하는 중…</p>
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
              const trend = trendBadge(prediction.trend)
              const courseSong = view === 'course' ? courseByKey.get(prediction.songKey) : undefined
              return (
                <Fragment key={prediction.songKey}>
                {view === 'timeline' && index === encoreStartIndex && (
                  <li className="encore-divider">Encore</li>
                )}
                {/* 체크 버튼은 링크의 형제다 — 앵커 안에 인터랙티브 요소를 두면 HTML 비준수 */}
                <li
                  className={practiced ? 'prediction-item practiced' : 'prediction-item'}
                >
                  <Link
                    to={`/events/${eventId}/songs/${encodeURIComponent(prediction.songKey)}`}
                    className="prediction-link"
                  >
                    <div className="prediction-row">
                      <span className="prediction-rank">
                        {view === 'probability' ? prediction.rank : index + 1}
                      </span>
                      <span className="prediction-song">
                        {prediction.songName}
                        {trend && (
                          <span
                            className={`trend-badge ${prediction.trend === 'RISING' ? 'up' : 'down'}`}
                            title={trend.label}
                          >
                            {trend.arrow}
                          </span>
                        )}
                        {played === true && <span className="hit-badge">적중</span>}
                        {played === false && <span className="miss-badge">미연주</span>}
                      </span>
                      <span className="prediction-percent">
                        {formatPercent(prediction.probability)}
                      </span>
                    </div>
                    {view === 'probability' && <ProbabilityBar probability={prediction.probability} />}
                    {courseSong ? (
                      <p className="prediction-evidence">
                        <span className={`tier-badge tier-${courseSong.tier.toLowerCase()}`}>
                          {TIER_LABELS[courseSong.tier]}
                        </span>{' '}
                        {courseSong.reason}
                      </p>
                    ) : (
                      <p className="prediction-evidence">
                        {evidenceText(prediction.playedCount, prediction.sampleSize)}
                        {position && <> · {position}</>}
                        {isEncoreStaple(prediction.encoreRatio) && (
                          <span className="encore-badge">앙코르 단골</span>
                        )}
                      </p>
                    )}
                  </Link>
                  <button
                    type="button"
                    className={practiced ? 'practice-check checked' : 'practice-check'}
                    aria-label={practiced ? '예습 완료 해제' : '예습 완료 표시'}
                    aria-pressed={practiced}
                    onClick={() => toggle(prediction.songKey)}
                  >
                    ✓
                  </button>
                </li>
                </Fragment>
              )
            })}
          </ol>
        </>
      )}

      {predictions && predictions.length > 0 && <SimilarShowsSection eventId={eventId} />}

      {predictions && predictions.length > 0 && <ChatSection eventId={eventId} />}
    </>
  )
}
