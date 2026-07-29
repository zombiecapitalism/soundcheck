import { Link, useParams } from 'react-router'
import { usePredictions } from '../api/queries'
import StatusView from '../components/StatusView'
import { evidenceText, formatPercent, isEncoreStaple, positionText } from '../lib/format'

/** 곡 상세 — RAG(F4)가 붙기 전까지는 예측 근거 요약 + 플레이스홀더. */
export default function SongPage() {
  const params = useParams()
  const eventId = Number(params.eventId)
  const songKey = decodeURIComponent(params.songKey ?? '')
  // 예측 목록 캐시에서 곡을 고른다 — 상세 화면 전용 API가 아직 없다
  const { data: predictions, isPending, isError, error, refetch } = usePredictions(eventId)

  if (isPending) {
    return <StatusView kind="loading" message="곡 정보를 불러오는 중…" />
  }
  if (isError) {
    return <StatusView kind="error" message={error.message} onRetry={() => refetch()} />
  }

  const song = predictions.find((prediction) => prediction.songKey === songKey)
  if (!song) {
    return <StatusView kind="empty" message="곡을 찾을 수 없어요." />
  }

  const position = positionText(song.avgPosition)
  return (
    <>
      <Link to={`/events/${eventId}`} className="back-link">
        ← 예측 목록
      </Link>
      <header className="song-header">
        <h1 className="page-title">{song.songName}</h1>
        <p className="song-summary">
          연주 확률 <strong>{formatPercent(song.probability)}</strong> ·{' '}
          {evidenceText(song.playedCount, song.sampleSize)}
          {position && <> · {position}</>}
          {isEncoreStaple(song.encoreRatio) && <span className="encore-badge">앙코르 단골</span>}
        </p>
      </header>
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
