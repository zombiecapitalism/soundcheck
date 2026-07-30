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
  const [keys, setKeys] = useState<string[]>(() => load(eventId))

  const toggle = (songKey: string) => {
    setKeys((prev) => {
      const next = toggleKey(prev, songKey)
      save(eventId, next)
      return next
    })
  }

  return { checkedKeys: new Set(keys), toggle }
}
