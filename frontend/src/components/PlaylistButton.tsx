import { useState } from 'react'
import { api } from '../api/client'

/**
 * 묶음 듣기(E12) — 선택 곡을 YouTube 임시 재생목록으로 연다.
 * 팝업 차단 회피: 클릭 시점에 빈 창을 동기로 열어두고, 응답이 오면 주소를 바꾼다
 * (fetch 이후의 window.open은 팝업으로 차단된다). 그래도 차단되면(win null)
 * 직접 누를 수 있는 링크를 노출한다 — 성공했는데 무반응이면 재클릭으로 쿼터만 탄다.
 */
export default function PlaylistButton({
  eventId,
  songKeys,
  label,
}: {
  eventId: number
  songKeys: string[]
  label: string
}) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [missing, setMissing] = useState<string[]>([])
  const [fallbackUrl, setFallbackUrl] = useState<string | null>(null)

  const listen = async () => {
    if (songKeys.length === 0 || loading) {
      return
    }
    const win = window.open('', '_blank')
    setLoading(true)
    setError(null)
    setMissing([])
    setFallbackUrl(null)
    try {
      const result = await api.createPlaylist(eventId, songKeys)
      if (result.url) {
        if (win) {
          win.location.href = result.url
        } else {
          setFallbackUrl(result.url)
        }
        setMissing(result.missing.map((item) => item.songName))
      } else {
        win?.close()
        setError('재생할 영상을 찾지 못했어요.')
      }
    } catch (e) {
      win?.close()
      setError(e instanceof Error ? e.message : '재생목록을 만들지 못했어요.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="playlist-row">
      <button
        type="button"
        className="playlist-button"
        disabled={songKeys.length === 0 || loading}
        onClick={() => void listen()}
      >
        ▶ {loading ? '재생목록 만드는 중…' : `YouTube로 듣기 · ${label}`}
      </button>
      {fallbackUrl && (
        <p className="playlist-missing">
          팝업이 차단됐어요 —{' '}
          <a href={fallbackUrl} target="_blank" rel="noreferrer">
            여기를 눌러 재생목록 열기 ↗
          </a>
        </p>
      )}
      {error && <p className="form-error">{error}</p>}
      {missing.length > 0 && (
        <p className="playlist-missing">영상을 못 찾은 곡: {missing.join(', ')}</p>
      )}
    </div>
  )
}
