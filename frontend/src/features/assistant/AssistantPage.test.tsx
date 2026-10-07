import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AiStatus, ConversationView } from '@/lib/api/types'
import { authStatus, CATEGORIES, COMMODITIES, makeAccount } from '@/test/fixtures'
import { renderRoutes, stubApi } from '@/test/utils'
import { AssistantPage } from './AssistantPage'

const STATUS: AiStatus = { enabled: true, shareDescriptions: true, tasks: { CHAT: true, EXTRACT: true, REPORT: true }, quota: { limit: 100, used: 0 } }
const QUESTION = 'این ماه کجا بیشتر خرج کردم؟'
const ANSWER = 'بیشترین هزینه **مسکن** بود: 18,000,000 تومان (63.2%).'

const STORED: ConversationView = {
  id: 7, title: QUESTION, model: 'claude-opus-5-5', providerName: 'Claude', available: true, full: false, running: false,
  items: [
    { kind: 'user', turn: 1, text: QUESTION },
    { kind: 'tool', turn: 1, toolId: 'c1', tool: 'summarize_by_category', label: 'تفکیک بر اساس دسته', status: 'done' },
    { kind: 'assistant', turn: 1, text: ANSWER },
  ],
}

function event(name: string, data: unknown) {
  return new TextEncoder().encode(`event:${name}\ndata:${JSON.stringify(data)}\n\n`)
}

/** The API, with /ai/chat answering through a stream the test writes to. */
function setup(chat: () => Response) {
  const sent: unknown[] = []
  const json = stubApi((url) => {
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/ai/status') return { body: STATUS }
    if (url === '/ai/conversations') return { body: [] }
    if (url === '/ai/conversations/7') return { body: STORED }
    if (url.startsWith('/accounts')) return { body: [makeAccount({})] }
    if (url === '/categories') return { body: CATEGORIES }
    if (url === '/commodities') return { body: COMMODITIES }
  })
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init: RequestInit = {}) => {
    if (String(input).endsWith('/ai/chat')) {
      sent.push(JSON.parse(String(init.body)))
      return Promise.resolve(chat())
    }
    return json(input, init)
  }))
  const view = renderRoutes([{ path: '/assistant/:id?', element: <AssistantPage /> }], '/assistant')
  return { sent, view, user: userEvent.setup() }
}

describe('AssistantPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('streams a turn live, then shows the stored conversation', async () => {
    let writer!: ReadableStreamDefaultController<Uint8Array>
    const body = new ReadableStream<Uint8Array>({ start: (c) => { writer = c } })
    const { sent, view, user } = setup(() => new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream' } }))

    expect(await screen.findByText('سلام! دستیار مالی شما هستم')).toBeInTheDocument()
    await user.type(screen.getByLabelText('پیام'), QUESTION)
    await user.keyboard('{Enter}')

    writer.enqueue(event('start', { conversationId: 7, title: QUESTION, turn: 1, created: true }))
    writer.enqueue(event('tool', { id: 'c1', name: 'summarize_by_category', label: 'تفکیک بر اساس دسته', status: 'running' }))
    expect(await screen.findByText('تفکیک بر اساس دسته')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'توقف پاسخ' })).toBeInTheDocument()
    await waitFor(() => expect(view.router.state.location.pathname).toBe('/assistant/7'))

    writer.enqueue(event('tool', { id: 'c1', name: 'summarize_by_category', label: 'تفکیک بر اساس دسته', status: 'done' }))
    // an event split across two reads
    const text = event('text', { delta: ANSWER })
    writer.enqueue(text.slice(0, 20))
    writer.enqueue(text.slice(20))
    writer.enqueue(event('done', { turn: 1 }))
    writer.close()

    // numbers in the answer are shown in Persian form
    expect(await screen.findByText(/۱۸٬۰۰۰٬۰۰۰ تومان \(۶۳٫۲٪\)/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'ارسال' })).toBeInTheDocument())
    expect(screen.getAllByText('تفکیک بر اساس دسته')).toHaveLength(1)
    expect(screen.getByText('claude-opus-5-5')).toBeInTheDocument()
    expect(sent).toEqual([{ conversationId: null, text: QUESTION }])
  })

  it('keeps a refused message on screen with the reason', async () => {
    const { user } = setup(() => new Response(JSON.stringify({ detail: 'سهمیه‌ی امروز شما تمام شده است.', code: 'ai.quotaExceeded' }),
      { status: 429, headers: { 'content-type': 'application/json' } }))
    await user.click(await screen.findByRole('button', { name: 'وضعیت بودجه‌ی این ماه چطور است؟' }))
    expect(await screen.findByText('سهمیه‌ی امروز شما تمام شده است.')).toBeInTheDocument()
    expect(screen.getByText('وضعیت بودجه‌ی این ماه چطور است؟')).toBeInTheDocument()
    expect(screen.queryByText('سلام! دستیار مالی شما هستم')).not.toBeInTheDocument()
  })
})
