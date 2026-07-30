import { Link, useParams } from 'react-router'
import { useEvent, usePredictionDetail } from '../api/queries'
import StatusView from '../components/StatusView'
import { useSongStory } from '../hooks/useSongStory'
import {
  evidenceText,
  formatEventDate,
  formatPercent,
  positionText,
  songRoleLabels,
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
      {story.status === 'error' && <p className="story-error">{story.errorMessage}</p>}
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
