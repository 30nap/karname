import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { authStatus, COMMODITIES, makeMe } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import type { Commodity, SecurityInstrument } from '@/lib/api/types'
import { AssetsPage } from './AssetsPage'

const FOOLAD: SecurityInstrument = { instrumentId: '46348559193224090', symbol: 'فولاد', nameFa: 'فولاد مبارکه اصفهان' }
const PERSONAL_SECURITY: Commodity = { code: 'C_TEST', nameFa: 'سهام من', unitFa: 'سهم', kind: 'SECURITY', scale: 0, custom: true, latestPrice: null }
const LINKED_SECURITY: Commodity = {
  ...PERSONAL_SECURITY,
  stock: { instrumentId: FOOLAD.instrumentId, symbol: FOOLAD.symbol, lastAttemptAt: '2026-10-14T08:00:00Z', lastSuccessAt: '2026-10-14T08:00:00Z', lastError: null },
  latestPrice: { priceToman: '235.5', pricedAt: '2026-10-14T08:00:00Z', source: 'TSETMC', personal: true, stale: false },
}

function setup(initial: Commodity[] = [], options: { searchError?: boolean; noResults?: boolean; quoteError?: boolean } = {}) {
  let commodities = [...COMMODITIES, ...initial]
  const requests: { path: string; method: string; body: unknown }[] = []
  const fetch = stubApi((url, init) => {
    if (url === '/auth/status') return { body: authStatus({ ...makeMe(), role: 'USER' }) }
    if (url === '/commodities' && init.method === 'POST') {
      requests.push({ path: url, method: init.method, body: JSON.parse(String(init.body)) })
      return { status: 201, body: PERSONAL_SECURITY }
    }
    if (url === '/commodities') return { body: commodities }
    if (url.startsWith('/accounts')) return { body: [] }
    if (url.startsWith('/prices')) return { body: [] }
    if (url.startsWith('/securities/search')) {
      if (options.searchError) return { status: 502, body: { detail: 'سرویس بورس در دسترس نیست.' } }
      return { body: options.noResults ? [] : [FOOLAD] }
    }
    if (url === '/securities' || url.startsWith(`/commodities/${PERSONAL_SECURITY.code}/security`)) {
      requests.push({ path: url, method: init.method!, body: init.body ? JSON.parse(String(init.body)) : undefined })
      const updated: Commodity = init.method === 'DELETE' ? { ...LINKED_SECURITY, stock: null } : {
        ...LINKED_SECURITY,
        stock: { ...LINKED_SECURITY.stock!, lastError: options.quoteError ? 'دریافت قیمت از بورس ناموفق بود.' : null },
        latestPrice: url === '/securities' && options.quoteError ? null : LINKED_SECURITY.latestPrice,
      }
      commodities = [...COMMODITIES, updated]
      return { status: init.method === 'DELETE' ? 204 : 200, body: updated }
    }
  })
  return { user: userEvent.setup(), requests, fetch }
}

async function chooseSecurity(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('نماد یا نام شرکت و صندوق'), 'فولاد')
  await user.click(screen.getByRole('button', { name: 'جست‌وجو' }))
  await user.click(await screen.findByRole('radio', { name: /فولاد مبارکه اصفهان/ }))
}

describe('Iranian securities', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('lets an ordinary user search and add an Iranian security with automatic pricing', async () => {
    const { user, requests, fetch } = setup()
    renderWithProviders(<AssetsPage />)
    await user.click(await screen.findByRole('button', { name: 'واحد جدید' }))
    await user.click(screen.getByRole('radio', { name: 'بورس ایران' }))
    expect(screen.getByRole('button', { name: 'افزودن نماد' })).toBeDisabled()
    expect(fetch.mock.calls.some(([url]) => String(url).includes('/securities/search'))).toBe(false)
    await chooseSecurity(user)
    await user.click(screen.getByRole('button', { name: 'افزودن نماد' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(requests).toEqual([{ path: '/securities', method: 'POST', body: { instrumentId: FOOLAD.instrumentId } }])
    expect(await screen.findByText(/نماد فولاد · قیمت خودکار/)).toBeInTheDocument()
  })

  it('keeps manual asset creation available', async () => {
    const { user, requests } = setup()
    renderWithProviders(<AssetsPage />)
    await user.click(await screen.findByRole('button', { name: 'واحد جدید' }))
    await user.type(screen.getByLabelText('نام'), 'سهام شخصی')
    await user.type(screen.getByLabelText('واحد شمارش'), 'سهم')
    await user.click(screen.getByRole('button', { name: 'ساخت' }))
    await waitFor(() => expect(requests).toEqual([
      { path: '/commodities', method: 'POST', body: { nameFa: 'سهام شخصی', unitFa: 'سهم', kind: 'SECURITY', scale: 0 } },
    ]))
  })

  it('keeps a newly added symbol visible when its first quote cannot be fetched', async () => {
    const { user, requests } = setup([], { quoteError: true })
    renderWithProviders(<AssetsPage />)
    await user.click(await screen.findByRole('button', { name: 'واحد جدید' }))
    await user.click(screen.getByRole('radio', { name: 'بورس ایران' }))
    await chooseSecurity(user)
    await user.click(screen.getByRole('button', { name: 'افزودن نماد' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(requests).toHaveLength(1)
    expect(await screen.findByText('دریافت قیمت نماد ناموفق بود؛ برای جزئیات و تلاش دوباره باز کنید.')).toBeInTheDocument()
    await user.click(screen.getByText(PERSONAL_SECURITY.nameFa))
    expect(screen.getByRole('button', { name: 'دریافت قیمت نماد' })).toBeInTheDocument()
    expect(screen.getByText(/دریافت قیمت از بورس ناموفق بود\. قیمت قبلی/)).toBeInTheDocument()
  })

  it('shows a search error and leaves adding disabled until a symbol is chosen', async () => {
    const { user, requests } = setup([], { searchError: true })
    renderWithProviders(<AssetsPage />)
    await user.click(await screen.findByRole('button', { name: 'واحد جدید' }))
    await user.click(screen.getByRole('radio', { name: 'بورس ایران' }))
    await user.type(screen.getByLabelText('نماد یا نام شرکت و صندوق'), 'فولاد')
    await user.click(screen.getByRole('button', { name: 'جست‌وجو' }))
    expect(await screen.findByText('سرویس بورس در دسترس نیست.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'افزودن نماد' })).toBeDisabled()
    expect(requests).toHaveLength(0)
  })

  it('clears a selected symbol when the search text changes', async () => {
    const { user } = setup()
    renderWithProviders(<AssetsPage />)
    await user.click(await screen.findByRole('button', { name: 'واحد جدید' }))
    await user.click(screen.getByRole('radio', { name: 'بورس ایران' }))
    await chooseSecurity(user)
    expect(screen.getByRole('button', { name: 'افزودن نماد' })).toBeEnabled()
    await user.type(screen.getByLabelText('نماد یا نام شرکت و صندوق'), 'ا')
    expect(screen.getByRole('button', { name: 'افزودن نماد' })).toBeDisabled()
    expect(screen.queryByRole('radiogroup', { name: 'نتایج جست‌وجوی نماد' })).not.toBeInTheDocument()
  })

  it('links an existing security and updates the open price dialog from the refreshed commodity', async () => {
    const { user, requests } = setup([PERSONAL_SECURITY])
    renderWithProviders(<AssetsPage />)
    await user.click(await screen.findByText(PERSONAL_SECURITY.nameFa))
    await user.click(screen.getByRole('button', { name: 'اتصال به نماد بورس' }))
    await chooseSecurity(user)
    await user.click(screen.getByRole('button', { name: 'اتصال نماد' }))
    expect(await screen.findByText('قیمت خودکار بورس ایران · فولاد')).toBeInTheDocument()
    expect(requests).toEqual([{ path: `/commodities/${PERSONAL_SECURITY.code}/security`, method: 'PUT', body: { instrumentId: FOOLAD.instrumentId } }])
    expect(screen.getByRole('button', { name: 'دریافت قیمت نماد' })).toBeInTheDocument()
  })

  it('shows a failed quote refresh without losing the previous price, and can stop automatic pricing', async () => {
    const { user, requests } = setup([LINKED_SECURITY], { quoteError: true })
    renderWithProviders(<AssetsPage />)
    await user.click(await screen.findByText(PERSONAL_SECURITY.nameFa))
    await user.click(screen.getByRole('button', { name: 'دریافت قیمت نماد' }))
    expect(await within(screen.getByRole('dialog')).findByText(/دریافت قیمت از بورس ناموفق بود/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'توقف دریافت خودکار' }))
    expect(await screen.findByRole('button', { name: 'اتصال به نماد بورس' })).toBeInTheDocument()
    expect(screen.queryByText(/قیمت خودکار بورس ایران · فولاد/)).not.toBeInTheDocument()
    expect(requests.map(({ path, method }) => ({ path, method }))).toEqual([
      { path: `/commodities/${PERSONAL_SECURITY.code}/security/refresh`, method: 'POST' },
      { path: `/commodities/${PERSONAL_SECURITY.code}/security`, method: 'DELETE' },
    ])
    await user.keyboard('{Escape}')
    expect(screen.getByText('۲۳۶ تومان')).toBeInTheDocument()
  })
})
