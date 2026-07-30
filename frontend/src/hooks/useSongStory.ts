import { useEffect, useState } from 'react'
import type { ExplanationSource } from '../api/types'

// 곡 배경 설명 SSE 스트림. TanStack Query는 단발 응답 모델이라 스트리밍 점진 표시에
// 맞지 않는다 — 이 훅만 EventSource + useState로 직접 관리한다(상태 라이브러리 금지 규칙 유지).

export interface SongStory {
  status: 'idle' | 'streaming' | 'done' | 'error'
  sources: ExplanationSource[]
  text: string
  errorMessage: string | null
}

const initial: SongStory = { status: 'idle', sources: [], text: '', errorMessage: null }

// 세션 내 재방문 캐시 — 같은 곡을 다시 열어도 LLM을 다시 부르지 않는다(비용·지연)
const storyCache = new Map<string, { sources: ExplanationSource[]; text: string }>()

export function useSongStory(
  artistMbid: string | undefined,
  songKey: string,
  songName: string | undefined,
): SongStory {
  const [story, setStory] = useState<SongStory>(initial)

  useEffect(() => {
    if (!artistMbid || !songName || !songKey) {
      return
    }
    const cacheKey = `${artistMbid}:${songKey}`
    const cached = storyCache.get(cacheKey)
    if (cached) {
      setStory({ status: 'done', ...cached, errorMessage: null })
      return
    }

    setStory({ ...initial, status: 'streaming' })
    // 이벤트 콜백 간 누적값 — setState 함수형 업데이트만으로는 sources/text가 서로를 덮는다
    let sources: ExplanationSource[] = []
    let text = ''
    const es = new EventSource(
      `/api/songs/${encodeURIComponent(songKey)}/explanation` +
        `?artistMbid=${encodeURIComponent(artistMbid)}&songName=${encodeURIComponent(songName)}`,
    )
    es.addEventListener('sources', (e) => {
      sources = JSON.parse(e.data) as ExplanationSource[]
      setStory({ status: 'streaming', sources, text, errorMessage: null })
    })
    es.addEventListener('delta', (e) => {
      // 서버가 토큰을 JSON 문자열로 감싼다 — SSE 규격의 선행 공백 제거를 피하기 위해
      text += JSON.parse(e.data) as string
      setStory({ status: 'streaming', sources, text, errorMessage: null })
    })
    es.addEventListener('done', () => {
      es.close()
      storyCache.set(cacheKey, { sources, text })
      setStory({ status: 'done', sources, text, errorMessage: null })
    })
    es.addEventListener('error', (e) => {
      // 서버가 보낸 error 이벤트(data 있음)와 연결 실패(Event) 모두 여기로 온다.
      // 닫지 않으면 EventSource가 자동 재연결해 생성을 반복한다 — 반드시 닫는다.
      es.close()
      const data = (e as MessageEvent<string>).data
      setStory({
        status: 'error',
        sources,
        text,
        errorMessage: data ? (JSON.parse(data) as string) : '설명을 불러오지 못했어요.',
      })
    })
    return () => es.close()
  }, [artistMbid, songKey, songName])

  return story
}
