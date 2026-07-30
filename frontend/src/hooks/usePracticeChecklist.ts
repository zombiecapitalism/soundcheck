import { useState } from 'react'
import { toggleKey } from '../lib/practice'

// 예습 체크는 기기 로컬의 개인 상태다 — 서버에 둘 이유가 없어 localStorage에 보관한다.
// (규칙: 서버 상태는 Query, 로컬은 useState — 상태 관리 라이브러리 없음)

function storageKey(eventId: number): string {
  return `encore-practice-${eventId}`
}

function load(eventId: number): string[] {
  try {
    const raw = localStorage.getItem(storageKey(eventId))
    const parsed = raw ? (JSON.parse(raw) as unknown) : []
    return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string') : []
  } catch {
    return [] // 손상된 저장값·프라이빗 모드 등은 빈 상태로 시작
  }
}

function save(eventId: number, keys: string[]) {
  try {
    localStorage.setItem(storageKey(eventId), JSON.stringify(keys))
  } catch {
    // 저장 실패(용량 등)해도 화면 동작은 유지
  }
}

export function usePracticeChecklist(eventId: number) {
  const [state, setState] = useState(() => ({ eventId, keys: load(eventId) }))

  // 라우터가 같은 컴포넌트를 재사용해 eventId만 바뀌면(마운트 없음) 이전 이벤트의
  // 체크가 새 이벤트 키에 저장된다 — 렌더 중 감지해 즉시 리셋한다(React 공식 패턴)
  if (state.eventId !== eventId) {
    setState({ eventId, keys: load(eventId) })
  }

  const toggle = (songKey: string) => {
    setState((prev) => {
      const next = toggleKey(prev.keys, songKey)
      save(prev.eventId, next)
      return { eventId: prev.eventId, keys: next }
    })
  }

  return { checkedKeys: new Set(state.keys), toggle }
}
