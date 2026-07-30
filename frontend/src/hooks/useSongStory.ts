import { useEffect, useState } from 'react'
import type { ExplanationSource } from '../api/types'

// 곡 배경 설명 SSE 스트림. TanStack Query는 단발 응답 모델이라 스트리밍 점진 표시에
// 맞지 않는다 — 이 훅만 EventSource + useState로 직접 관리한다(상태 라이브러리 금지 규칙 유지).

interface StoryState {
  status: 'idle' | 'streaming' | 'done' | 'error'
  sources: ExplanationSource[]
  text: string
  errorMessage: string | null
}

export interface SongStory extends StoryState {
  /** 에러 시 같은 곡을 처음부터 다시 생성한다. */
  retry: () => void
}

const initial: StoryState = { status: 'idle', sources: [], text: '', errorMessage: null }

// 세션 내 재방문 캐시 — 같은 곡을 다시 열어도 서버를 다시 부르지 않는다(서버 캐시와 별개)
const storyCache = new Map<string, { sources: ExplanationSource[]; text: string }>()

// 곡명은 보내지 않는다 — 서버가 예측 스냅샷의 원본 곡명을 쓴다(프롬프트 주입·캐시 오염 방지)
export function useSongStory(artistMbid: string | undefined, songKey: string): SongStory {
  const key = `${artistMbid ?? ''}:${songKey}`
  const [state, setState] = useState(() => ({ key, attempt: 0, story: initial }))

  // 곡이 바뀌었는데 컴포넌트가 재사용되면(마운트 없음) 이전 곡의 본문이 새 곡 화면에
  // 남는다 — 렌더 중 감지해 즉시 리셋한다(usePracticeChecklist와 같은 패턴)
  if (state.key !== key) {
    setState({ key, attempt: 0, story: initial })
  }
  const attempt = state.key === key ? state.attempt : 0

  useEffect(() => {
    if (!artistMbid || !songKey) {
      return
    }
    const cached = storyCache.get(key)
    if (cached) {
      setState({ key, attempt, story: { status: 'done', ...cached, errorMessage: null } })
      return
    }

    // 이벤트 콜백 간 누적값 — setState 함수형 업데이트만으로는 sources/text가 서로를 덮는다
    let sources: ExplanationSource[] = []
    let text = ''
    // 늦게 도착한 이전 곡 이벤트가 새 곡 상태를 덮어도, key가 달라 다음 렌더에서 리셋된다
    const update = (story: StoryState) => setState({ key, attempt, story })
    update({ ...initial, status: 'streaming' })

    const es = new EventSource(
      `/api/songs/${encodeURIComponent(songKey)}/explanation?artistMbid=${encodeURIComponent(artistMbid)}`,
    )
    es.addEventListener('sources', (e) => {
      sources = JSON.parse(e.data) as ExplanationSource[]
      update({ status: 'streaming', sources, text, errorMessage: null })
    })
    es.addEventListener('delta', (e) => {
      // 서버가 토큰을 JSON 문자열로 감싼다 — SSE 규격의 선행 공백 제거를 피하기 위해
      text += JSON.parse(e.data) as string
      update({ status: 'streaming', sources, text, errorMessage: null })
    })
    es.addEventListener('done', () => {
      es.close()
      storyCache.set(key, { sources, text })
      update({ status: 'done', sources, text, errorMessage: null })
    })
    es.addEventListener('error', (e) => {
      // 서버가 보낸 error 이벤트(data 있음)와 연결 실패(Event) 모두 여기로 온다.
      // 닫지 않으면 EventSource가 자동 재연결해 생성을 반복한다 — 반드시 닫는다.
      es.close()
      const data = (e as MessageEvent<string>).data
      update({
        status: 'error',
        sources,
        text,
        errorMessage: data ? (JSON.parse(data) as string) : '설명을 불러오지 못했어요.',
      })
    })
    return () => es.close()
  }, [key, attempt, artistMbid, songKey])

  const retry = () =>
    setState((prev) => ({ key, attempt: (prev.key === key ? prev.attempt : 0) + 1, story: initial }))

  return { ...state.story, retry }
}
