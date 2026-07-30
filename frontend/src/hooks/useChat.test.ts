import { describe, expect, it } from 'vitest'
import { parseSseChunk } from './useChat'

/** SSE 파서(순수 함수) — 청크 경계·다중 data 줄·이벤트명 누락 처리를 검증한다. */
describe('parseSseChunk', () => {
  it('완결된 블록을 이벤트로 파싱한다', () => {
    const { events, rest } = parseSseChunk('event:delta\ndata:"안녕"\n\nevent:done\ndata:{}\n\n')
    expect(events).toEqual([
      { event: 'delta', data: '"안녕"' },
      { event: 'done', data: '{}' },
    ])
    expect(rest).toBe('')
  })

  it('청크 경계에 걸린 블록은 rest 버퍼로 남긴다 — 다음 청크와 이어 붙여야 한다', () => {
    const first = parseSseChunk('event:delta\ndata:"완결"\n\nevent:delta\ndata:"잘')
    expect(first.events).toHaveLength(1)
    expect(first.rest).toBe('event:delta\ndata:"잘')

    const second = parseSseChunk(first.rest + '림"\n\n')
    expect(second.events).toEqual([{ event: 'delta', data: '"잘림"' }])
    expect(second.rest).toBe('')
  })

  it('data 여러 줄은 개행으로 합친다 (SSE 규격)', () => {
    const { events } = parseSseChunk('event:sources\ndata:[1,\ndata:2]\n\n')
    expect(events).toEqual([{ event: 'sources', data: '[1,\n2]' }])
  })

  it('"data:" 뒤 공백 하나는 규격대로 벗긴다', () => {
    const { events } = parseSseChunk('event:delta\ndata: " 공백"\n\n')
    expect(events).toEqual([{ event: 'delta', data: '" 공백"' }])
  })

  it('event 줄이 없으면 기본 이벤트명 message, data 없는 블록은 무시한다', () => {
    const { events } = parseSseChunk('data:x\n\nevent:only-name\n\n')
    expect(events).toEqual([{ event: 'message', data: 'x' }])
  })
})
