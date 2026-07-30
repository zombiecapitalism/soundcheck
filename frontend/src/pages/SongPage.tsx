import { Link, useParams } from 'react-router'
import { useEvent, usePredictionDetail } from '../api/queries'
import StatusView from '../components/StatusView'
import { useSongStory } from '../hooks/useSongStory'
import {
  boostEffectText,
  confidenceText,
  evidenceText,
  formatEventDate,
  formatPercent,
  positionSegments,
  positionText,
  songRoleLabels,
  trendBadge,
  typeBreakdownText,
} from '../lib/format'
import { listenLinks } from '../lib/links'

/** 곡 상세 — 예측 근거를 최근 공연 타임라인으로 풀어 보여준다. RAG(F4)는 아래 플레이스홀더에 연결 예정. */
export default function SongPage() {
  const params = useParams()
  const eventId = Number(params.eventId)
  // react-router가 params를 이미 디코드해서 준다
  const songKey = params.songKey ?? ''
  const { data, isPending, isError, error, refetch } = usePredictionDetail(eventId, songKey)
  const { data: event } = useEvent(eventId) // 듣기 링크의 아티스트명 (목록 캐시 공유)

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

  const { prediction, confidence, evidence, history } = data
  const position = positionText(prediction.avgPosition)
  const labels = songRoleLabels(prediction)
  const playedCount = history.filter((entry) => entry.played).length
  const trend = trendBadge(prediction.trend)
  // 타임라인 막대의 상대 농도 — 가중치가 가장 큰 공연을 1로 정규화
  const maxWeight = Math.max(...history.map((entry) => entry.weight ?? 0), 0)

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
        {/* 신뢰도는 항상 있으므로 라벨 줄은 항상 그린다 */}
        <p className="song-labels">
          <span className={`confidence-badge confidence-${confidence.toLowerCase()}`}>
            {confidenceText(confidence)}
          </span>
          {trend && (
            <span className="role-badge">
              {trend.arrow} {trend.label}
            </span>
          )}
          {labels.map((label) => (
            <span key={label} className="role-badge">
              {label}
            </span>
          ))}
        </p>
        {event && (
          <div className="listen-links">
            {listenLinks(event.artist.name, prediction.songName).map((link) => (
              <a
                key={link.label}
                className="listen-button"
                href={link.url}
                target="_blank"
                rel="noreferrer"
              >
                {link.label} ↗
              </a>
            ))}
          </div>
        )}
      </header>

      {evidence && (
        <section className="evidence-card">
          <h2>왜 {formatPercent(prediction.probability)}인가</h2>
          <ul className="evidence-list">
            <li>
              <span className="evidence-term">단순 등장률</span>
              <span className="evidence-value">
                {formatPercent(evidence.baseFrequency)} ({prediction.playedCount}/
                {prediction.sampleSize}회)
              </span>
            </li>
            <li>
              <span className="evidence-term">최신성 가중</span>
              <span className="evidence-value">
                최근 공연일수록 가중 (감쇠 {evidence.recencyDecay})
                {prediction.recentCount5 != null && <> · 최근 5회 중 {prediction.recentCount5}회</>}
              </span>
            </li>
            {boostEffectText(evidence.boostEffect) && (
              <li>
                <span className="evidence-term">유형 부스트</span>
                <span className="evidence-value">
                  같은 유형 공연 ×{evidence.matchingShowTypeBoost} →{' '}
                  {boostEffectText(evidence.boostEffect)}
                </span>
              </li>
            )}
            {evidence.typeBreakdown && typeBreakdownText(evidence.typeBreakdown) && (
              <li>
                <span className="evidence-term">유형별 등장</span>
                <span className="evidence-value">{typeBreakdownText(evidence.typeBreakdown)}</span>
              </li>
            )}
          </ul>
          {evidence.positionStats && prediction.playedCount > 0 && (
            <div className="position-stats">
              <span className="evidence-term">등장 위치</span>
              <div className="position-segments">
                {positionSegments(evidence.positionStats, prediction.playedCount).map((segment) => (
                  <span key={segment.label} className="position-segment">
                    {segment.label} {segment.percent}%
                  </span>
                ))}
              </div>
            </div>
          )}
        </section>
      )}

      <section className="timeline-section">
        <h2>
          최근 공연 타임라인
          <span className="timeline-count">
            {playedCount}/{history.length}회 연주
          </span>
        </h2>
        {/* 한눈에 보는 연주 밀도 — 최근(왼쪽)부터. 농도 = 계산 기여 가중치(E1) */}
        <div className="play-strip" aria-hidden="true">
          {history.map((entry) => (
            <span
              key={entry.setlistId}
              className={entry.played ? 'strip-cell played' : 'strip-cell'}
              style={
                entry.played && entry.weight != null && maxWeight > 0
                  ? { opacity: 0.35 + 0.65 * (entry.weight / maxWeight) }
                  : undefined
              }
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

      <SongStorySection
        artistMbid={event?.artist.mbid}
        songKey={songKey}
        songName={prediction.songName}
      />
    </>
  )
}

/** RAG 곡 이야기 — SSE로 점진 표시. 출처가 항상 함께 나온다(없으면 "자료 없음" 상태). */
function SongStorySection({
  artistMbid,
  songKey,
  songName,
}: {
  artistMbid: string | undefined
  songKey: string
  songName: string
}) {
  const story = useSongStory(artistMbid, songKey, songName)
  // 근거가 부족하면 서버(프롬프트 계약)가 "정보 없음"을 보낸다 —
  // 모델이 마침표 등을 덧붙이는 경우가 있어(실측: "정보 없음.") 정확 일치가 아니라 패턴 판정
  const noInfo = story.status === 'done' && /^정보 없음[.!]?$/.test(story.text.trim())

  return (
    <section className="song-story">
      <h2>곡 이야기</h2>
      {story.status === 'streaming' && story.text === '' && (
        <p className="story-pending">배경 설명을 생성하는 중…</p>
      )}
      {noInfo ? (
        <p className="story-empty">아직 이 곡에 대해 수집된 배경 자료가 없어요.</p>
      ) : (
        story.text !== '' && (
          <p className="story-text">
            {story.text}
            {story.status === 'streaming' && <span className="story-cursor" aria-hidden="true" />}
          </p>
        )
      )}
      {story.status === 'error' && (
        <p className="story-error">
          {story.errorMessage}{' '}
          <button type="button" className="story-retry" onClick={story.retry}>
            다시 시도
          </button>
        </p>
      )}
      {story.sources.length > 0 && !noInfo && (
        <p className="story-sources">
          출처:{' '}
          {story.sources.map((source) => (
            <a key={source.url} href={source.url} target="_blank" rel="noreferrer">
              {source.title} ({source.name}) ↗
            </a>
          ))}
        </p>
      )}
    </section>
  )
}
