import { Link, useParams } from 'react-router'
import { usePredictions } from '../api/queries'
import StatusView from '../components/StatusView'
import { evidenceText, formatPercent, isEncoreStaple, positionText } from '../lib/format'

/** 곡 상세 — RAG(F4)가 붙기 전까지는 예측 근거 요약 + 플레이스홀더. */
export default function SongPage() {
  const params = useParams()
  const eventId = Number(params.eventId)
  // react-router가 params를 이미 디코드해서 준다 — 여기서 또 디코드하면 이중 디코드다
  const songKey = params.songKey ?? ''
  // 예측 목록 캐시에서 곡을 고른다 — 상세 화면 전용 API가 아직 없다
  const { data: predictions, isPending, isError, error, refetch } = usePredictions(eventId)

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
