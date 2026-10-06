import { api, ApiError, onUnauthorized, query } from './client'

function mockFetch(status: number, body: unknown, contentType = 'application/json') {
  const fn = vi.fn().mockResolvedValue(
    new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'content-type': contentType } }),
  )
  vi.stubGlobal('fetch', fn)
  return fn
}

describe('api client', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
  })

  it('sends the CSRF token from the cookie on mutations only', async () => {
    document.cookie = 'XSRF-TOKEN=abc-123; path=/'
    const fetchMock = mockFetch(200, { ok: true })
    await api.post('/accounts', { name: 'x' })
    expect(fetchMock.mock.calls[0][1].headers['X-XSRF-TOKEN']).toBe('abc-123')
    await api.get('/accounts')
    expect(fetchMock.mock.calls[1][1].headers['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('uploads files as multipart, letting the browser set the boundary', async () => {
    document.cookie = 'XSRF-TOKEN=abc-123; path=/'
    const fetchMock = mockFetch(200, { rows: [] })
    const form = new FormData()
    form.append('file', new File(['a,b'], 'statement.csv', { type: 'text/csv' }))
    await api.upload('/io/import/preview', form)
    const init = fetchMock.mock.calls[0][1]
    expect(init.method).toBe('POST')
    expect(init.body).toBe(form)
    expect(init.headers['Content-Type']).toBeUndefined()
    expect(init.headers['X-XSRF-TOKEN']).toBe('abc-123')
  })

  it('turns problem details into ApiError with Persian detail', async () => {
    mockFetch(400, { detail: 'بعضی از مقادیر واردشده معتبر نیستند.', code: 'error.validation', errors: [{ field: 'amount', message: 'الزامی است.' }] })
    const error = (await api.post('/transactions', {}).catch((e: unknown) => e)) as ApiError
    expect(error).toBeInstanceOf(ApiError)
    expect(error.message).toBe('بعضی از مقادیر واردشده معتبر نیستند.')
    expect(error.code).toBe('error.validation')
    expect(error.fieldErrors).toHaveLength(1)
  })

  it('notifies listeners about expired sessions', async () => {
    mockFetch(401, { detail: 'لطفاً وارد حساب کاربری شوید.', code: 'error.unauthorized' })
    const listener = vi.fn()
    const off = onUnauthorized(listener)
    await api.get('/me').catch(() => undefined)
    expect(listener).toHaveBeenCalledOnce()
    off()
  })

  it('maps network failures to a Persian message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const error = (await api.get('/x').catch((e: unknown) => e)) as ApiError
    expect(error.status).toBe(0)
    expect(error.message).toContain('ارتباط با سرور')
  })

  it('builds query strings skipping empty values', () => {
    expect(query({ from: '2026-01-01', to: '', type: undefined, tag: ['a', 'b'], page: 0 })).toBe('?from=2026-01-01&tag=a&tag=b&page=0')
  })
})
