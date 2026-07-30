import { useState } from 'react'
import type { FormEvent } from 'react'
import { useChat } from '../hooks/useChat'

const EXAMPLE_QUESTIONS = ['꼭 들어야 하는 곡은?', '신곡 나올 가능성은?', '앙코르에는 뭐가 나올까?']

/**
 * RAG Chat(E8) — 예측·배경 문서를 근거로 답하는 공연 예습 채팅.
 * 대화는 이 컴포넌트의 로컬 상태다(서버 세션 없음) — 페이지를 떠나면 사라진다.
 */
export default function ChatSection({ eventId }: { eventId: number }) {
  const chat = useChat(eventId)
  const [input, setInput] = useState('')

  const ask = (question: string) => {
    setInput('')
    void chat.send(question)
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    ask(input)
  }

  return (
    <section className="chat-section">
      <h2>물어보기</h2>
      <p className="chat-hint">예측 데이터와 수집된 배경 문서를 근거로만 답해요.</p>

      {chat.turns.length === 0 && (
        <div className="chat-examples">
          {EXAMPLE_QUESTIONS.map((question) => (
            <button
              key={question}
              type="button"
              className="chat-example-chip"
              disabled={chat.status === 'streaming'}
              onClick={() => ask(question)}
            >
              {question}
            </button>
          ))}
        </div>
      )}

      {chat.turns.length > 0 && (
        <ol className="chat-turns">
          {chat.turns.map((turn, index) => (
            <li key={index} className={`chat-turn ${turn.role}`}>
              <p className="chat-bubble">
                {turn.content}
                {chat.status === 'streaming' &&
                  index === chat.turns.length - 1 &&
                  turn.role === 'assistant' && <span className="story-cursor" aria-hidden="true" />}
              </p>
              {turn.sources && turn.sources.length > 0 && (
                <p className="chat-sources">
                  근거:{' '}
                  {turn.sources.map((source) =>
                    source.url ? (
                      <a key={source.url} href={source.url} target="_blank" rel="noreferrer">
                        {source.title} ↗
                      </a>
                    ) : (
                      <span key={source.title}>{source.title}</span>
                    ),
                  )}
                </p>
              )}
            </li>
          ))}
          {chat.status === 'streaming' &&
            chat.turns[chat.turns.length - 1]?.role === 'user' && (
              <li className="chat-turn assistant">
                <p className="chat-bubble chat-pending">생각하는 중…</p>
              </li>
            )}
        </ol>
      )}

      {chat.status === 'error' && chat.errorMessage && (
        <p className="form-error">{chat.errorMessage}</p>
      )}

      <form className="chat-input-row" onSubmit={submit}>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="이 공연에 대해 물어보세요 (최대 500자)"
          maxLength={500}
          disabled={chat.status === 'streaming'}
        />
        <button
          type="submit"
          className="primary-button"
          disabled={!input.trim() || chat.status === 'streaming'}
        >
          질문
        </button>
      </form>
    </section>
  )
}
