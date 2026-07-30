import { useRef, useState } from 'react'
import type { ExplanationSource } from '../api/types'

// RAG Chat(E8) — POST + SSE. EventSource는 POST를 못 쓰므로 fetch 스트림을 직접 파싱한다.
// 대화 이력은 서버 세션 없이 클라이언트가 함께 보낸다(stateless, 최근 6턴은 서버가 강제).

export interface ChatTurn {
  role: 'user' | 'assistant'
  content: string
  /** assistant 턴에만 — 답이 근거로 쓴 출처(도구 실행 결과) */
  sources?: ExplanationSource[]
}

interface ChatState {
  turns: ChatTurn[]
  status: 'idle' | 'streaming' | 'error'
  errorMessage: string | null
}

/** SSE 본문 파서 — "event: x\ndata: y\n\n" 블록 단위. 청크 경계에 걸친 블록은 버퍼에 남긴다. */
function parseSseChunk(buffer: string): { events: { event: string; data: string }[]; rest: string } {
  const events: { event: string; data: string }[] = []
  const blocks = buffer.split('\n\n')
  const rest = blocks.pop() ?? ''
  for (const block of blocks) {
    let event = 'message'
    const dataLines: string[] = []
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''))
      }
    }
    if (dataLines.length > 0) {
      events.push({ event, data: dataLines.join('\n') })
    }
  }
  return { events, rest }
}

export function useChat(eventId: number) {
  const [state, setState] = useState<ChatState>({ turns: [], status: 'idle', errorMessage: null })
  // 진행 중 요청 취소용 — 언마운트나 새 질문 시 이전 스트림을 닫는다
  const abortRef = useRef<AbortController | null>(null)

  const send = async (question: string) => {
    const trimmed = question.trim()
    if (!trimmed || state.status === 'streaming') {
      return
    }
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    const history = [...state.turns, { role: 'user' as const, content: trimmed }]
    let answer = ''
    let sources: ExplanationSource[] = []
    const update = (status: ChatState['status'], errorMessage: string | null = null) =>
      setState({
        turns: [...history, ...(answer ? [{ role: 'assistant' as const, content: answer, sources }] : [])],
        status,
        errorMessage,
      })
    update('streaming')

    try {
      const response = await fetch(`/api/events/${eventId}/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({
          messages: history.map((turn) => ({ role: turn.role, content: turn.content })),
        }),
        signal: controller.signal,
      })
      if (!response.ok || !response.body) {
        let message = `요청 실패 (${response.status})`
        try {
          const problem = (await response.json()) as { detail?: string }
          message = problem.detail ?? message
        } catch {
          /* Problem 형식이 아니면 기본 메시지 */
        }
        update('error', message)
        return
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let finished = false
      while (!finished) {
        const { done, value } = await reader.read()
        if (done) {
          break
        }
        buffer += decoder.decode(value, { stream: true })
        const { events, rest } = parseSseChunk(buffer)
        buffer = rest
        for (const sse of events) {
          if (sse.event === 'delta') {
            answer += JSON.parse(sse.data) as string
            update('streaming')
          } else if (sse.event === 'sources') {
            sources = JSON.parse(sse.data) as ExplanationSource[]
          } else if (sse.event === 'done') {
            finished = true
            // delta 없이 done만 오면(모델 빈 응답) 질문만 남고 아무 표시가 없다 — 오류로 알린다
            if (answer) {
              update('idle')
            } else {
              update('error', '답변을 받지 못했어요. 다시 시도해 주세요.')
            }
          } else if (sse.event === 'error') {
            finished = true
            update('error', JSON.parse(sse.data) as string)
          }
        }
      }
      if (!finished) {
        // done 없이 스트림이 끊김 — 받은 게 있으면 그만큼 보여주고, 없으면 오류로 알린다
        if (answer) {
          update('idle')
        } else {
          update('error', '연결이 끊겼어요. 다시 시도해 주세요.')
        }
      }
    } catch (e) {
      if (!controller.signal.aborted) {
        update('error', e instanceof Error ? e.message : '답변 생성에 실패했어요.')
      }
    }
  }

  return { ...state, send }
}
