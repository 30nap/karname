import { API_BASE, ApiError, readCookie } from '@/lib/api/client'
import type { AiDraft, ToolStatus } from '@/lib/api/types'

/** What the server reports while a reply is written. */
export type ChatEvent =
  | { type: 'start'; conversationId: number; title: string; turn: number; created: boolean }
  | { type: 'text'; delta: string }
  | { type: 'tool'; id: string; name: string; label: string; status: ToolStatus }
  | { type: 'drafts'; toolId: string; drafts: AiDraft[] }
  | { type: 'notice'; message: string }
  | { type: 'error'; message: string }
  | { type: 'done'; turn: number }

/** One Server-Sent Event block ("event: …" and "data: …" lines); null for comments and unknown events. */
export function parseEvent(block: string): ChatEvent | null {
  let name = 'message'
  const data: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith(':')) continue
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    let value = colon < 0 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') name = value
    else if (field === 'data') data.push(value)
  }
  if (data.length === 0) return null
  let payload: unknown
  try {
    payload = JSON.parse(data.join('\n'))
  } catch {
    return null
  }
  const known = ['start', 'text', 'tool', 'drafts', 'notice', 'error', 'done']
  return known.includes(name) ? ({ type: name, ...(payload as object) } as ChatEvent) : null
}

/** Splits a growing buffer into complete event blocks, returning them and what remains. */
export function splitEvents(buffer: string): { blocks: string[]; rest: string } {
  const normalized = buffer.replace(/\r\n?/g, '\n')
  const blocks: string[] = []
  let rest = normalized
  let index: number
  while ((index = rest.indexOf('\n\n')) >= 0) {
    blocks.push(rest.slice(0, index))
    rest = rest.slice(index + 2)
  }
  return { blocks, rest }
}

/**
 * Sends a chat message and calls {@code onEvent} for each event of the streamed reply. Errors
 * before streaming starts (quota, missing setup…) are thrown as {@link ApiError}.
 */
export async function streamChat(body: { conversationId: number | null; text: string }, onEvent: (event: ChatEvent) => void,
  signal?: AbortSignal): Promise<void> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json', Accept: 'text/event-stream' }
  const token = readCookie('XSRF-TOKEN')
  if (token) headers['X-XSRF-TOKEN'] = token
  let response: Response
  try {
    response = await fetch(`${API_BASE}/ai/chat`, { method: 'POST', headers, body: JSON.stringify(body), credentials: 'same-origin', signal })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new ApiError(0, null)
  }
  if (!response.ok || !response.body) {
    const data = await response.json().catch(() => null)
    throw new ApiError(response.status, data)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const { blocks, rest } = splitEvents(buffer)
    buffer = rest
    for (const block of blocks) {
      const event = parseEvent(block)
      if (event) onEvent(event)
    }
  }
  // a final event the server did not end with a blank line
  for (const block of splitEvents(buffer + decoder.decode() + '\n\n').blocks) {
    const event = parseEvent(block)
    if (event) onEvent(event)
  }
}
