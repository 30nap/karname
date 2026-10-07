import { describe, expect, it } from 'vitest'
import { parseEvent, splitEvents } from './stream'

describe('chat stream parsing', () => {
  it('reads named events with JSON data', () => {
    expect(parseEvent('event:text\ndata:{"delta":"سلام"}')).toEqual({ type: 'text', delta: 'سلام' })
    expect(parseEvent('event: tool\ndata: {"id":"c1","name":"calculate","label":"محاسبه","status":"running"}'))
      .toEqual({ type: 'tool', id: 'c1', name: 'calculate', label: 'محاسبه', status: 'running' })
  })

  it('joins multi-line data and ignores comments, unknown events and broken JSON', () => {
    expect(parseEvent(': keep-alive\nevent:notice\ndata:{"message":\ndata:"x"}')).toEqual({ type: 'notice', message: 'x' })
    expect(parseEvent(': ping')).toBeNull()
    expect(parseEvent('event:other\ndata:{}')).toBeNull()
    expect(parseEvent('event:text\ndata:{oops')).toBeNull()
  })

  it('keeps incomplete events in the buffer until the blank line arrives', () => {
    const first = splitEvents('event:text\ndata:{"delta":"a"}\n\nevent:text\ndata:{"del')
    expect(first.blocks).toEqual(['event:text\ndata:{"delta":"a"}'])
    const second = splitEvents(first.rest + 'ta":"b"}\r\n\r\n')
    expect(second.blocks.map(parseEvent)).toEqual([{ type: 'text', delta: 'b' }])
    expect(second.rest).toBe('')
  })
})
