import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { toast } from 'sonner'
import type { Commodity, PriceCoverage, PriceSource, PriceSourcePreset } from '@/lib/api/types'
import { authStatus, COMMODITIES } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { PriceSourcesCard } from './PriceSources'

const GOLD: Commodity = { code: 'GOLD18', nameFa: 'طلای ۱۸ عیار', unitFa: 'گرم', kind: 'GOLD', scale: 3, custom: false, latestPrice: null }
const USDT: Commodity = { code: 'USDT', nameFa: 'تتر', unitFa: 'تتر', kind: 'CRYPTO', scale: 2, custom: false, latestPrice: null }

const NOBITEX: PriceSource = {
  id: 1, name: 'نوبیتکس', kind: 'NOBITEX', url: 'https://apiv2.nobitex.ir', unit: 'RIAL', headers: [],
  mappings: [{ commodity: 'USDT', path: 'usdt', multiplier: null }], intervalMinutes: 30, enabled: false,
  priority: 10, preset: 'NOBITEX', usesKey: false, hasApiKey: false, lastRunAt: null, lastSuccessAt: null, lastError: null, lastCount: null, nextRunAt: null,
}
const GOLD_API: PriceSource = {
  id: 2, name: 'API طلا', kind: 'JSON', url: 'https://example.com/gold.json', unit: 'RIAL', headers: [{ name: 'X-Api-Key', hasValue: true }],
  mappings: [{ commodity: 'GOLD18', path: '/data/gold18', multiplier: null }], intervalMinutes: 60, enabled: true,
  priority: 20, preset: null, usesKey: false, hasApiKey: false, lastRunAt: '2026-10-06T08:00:00Z', lastSuccessAt: '2026-10-06T07:00:00Z', lastError: 'سرویس با کد HTTP ۵۰۳ پاسخ داد.', lastCount: 0,
  nextRunAt: '2026-10-06T09:00:00Z',
}

const KEYED: PriceSourcePreset = {
  id: 'NAVASAN', name: 'نوسان', description: 'ارز، طلا و سکه؛ با کلید رایگان.', coverage: 'GOLD_CURRENCY', kind: 'JSON',
  url: 'https://api.navasan.tech/latest/?api_key={key}', unit: 'TOMAN', headers: {}, intervalMinutes: 360, website: 'https://navasan.tech',
  keyHelp: 'کلید را از پنل نوسان بگیرید.', note: 'سهمیه‌ی ماهانه دارد.',
  mappings: [{ commodity: 'USD', path: '/usd_sell/value', multiplier: null }],
}

const COVERAGE: PriceCoverage[] = [{
  commodity: 'USDT', priceToman: '104000', fetchedAt: '2026-10-06T08:20:00Z', source: 'نوبیتکس', warning: 'منابع با هم اختلاف دارند.',
  quotes: [
    { sourceId: 1, sourceName: 'نوبیتکس', priceToman: '104000', fetchedAt: '2026-10-06T08:20:00Z', status: 'CHOSEN' },
    { sourceId: 2, sourceName: 'API طلا', priceToman: '1040000', fetchedAt: '2026-10-06T08:20:00Z', status: 'OUTLIER' },
  ],
  recordedPrice: '104000', recordedAt: '2026-10-06T08:20:00Z', recordedSource: 'نوبیتکس',
}, {
  // a jump nothing confirms: held, the good price stays
  commodity: 'GOLD18', priceToman: null, fetchedAt: null, source: null, warning: 'قیمت «API طلا» بیش از ۵۰٪ فرق دارد و ثبت نشد.',
  quotes: [{ sourceId: 2, sourceName: 'API طلا', priceToman: '89500000000', fetchedAt: '2026-10-06T08:20:00Z', status: 'HELD' }],
  recordedPrice: '8950000', recordedAt: '2026-10-06T07:00:00Z', recordedSource: 'API طلا',
}]

function setup() {
  const requests: { method: string; url: string; body?: unknown }[] = []
  stubApi((url, init) => {
    const method = init.method ?? 'GET'
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/commodities') return { body: [...COMMODITIES, GOLD, USDT] }
    if (method !== 'GET') {
      requests.push({ method, url, body: init.body ? JSON.parse(String(init.body)) : undefined })
      if (url === '/admin/price-sources/order') return { body: [{ ...GOLD_API, priority: 10 }, { ...NOBITEX, priority: 20 }] }
      if (url.endsWith('/run')) {
        return { body: { error: null, recorded: 0, results: [{ commodity: 'GOLD18', path: '/data/gold18', raw: '89500000', priceToman: '8950000', error: null }] } }
      }
      if (url === '/admin/price-sources' && (init.body as string).includes('"apiKey":null')) {
        return { status: 400, body: { detail: 'این منبع کلید API می‌خواهد؛ کلید را وارد کنید.', code: 'priceSource.keyRequired' } }
      }
      if (url.startsWith('/admin/price-sources/test')) {
        return { body: { error: null, recorded: 0, results: [{ commodity: 'GOLD18', path: '/data/gold18', raw: '89500000', priceToman: '8950000', error: null }] } }
      }
      return { body: GOLD_API }
    }
    if (url === '/admin/price-sources') return { body: [NOBITEX, GOLD_API] }
    if (url === '/admin/price-sources/presets') return { body: [KEYED] }
    if (url === '/admin/price-sources/coverage') return { body: COVERAGE }
  })
  return { requests, user: userEvent.setup() }
}

describe('PriceSourcesCard', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows each source with its last error and switches one on without resending secrets', async () => {
    const { requests, user } = setup()
    renderWithProviders(<PriceSourcesCard />)
    expect(await screen.findByText('سرویس با کد HTTP ۵۰۳ پاسخ داد.')).toBeInTheDocument()
    expect(screen.getByText('خاموش')).toBeInTheDocument()
    await user.click(screen.getByRole('switch', { name: 'دریافت خودکار از API طلا' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toMatchObject({ method: 'PUT', url: '/admin/price-sources/2', body: { enabled: false, headers: [{ name: 'X-Api-Key', value: null }] } })
  })

  it('tests a configuration before saving it', async () => {
    const { requests, user } = setup()
    renderWithProviders(<PriceSourcesCard />)
    await screen.findByText('API طلا')
    await user.click(screen.getByRole('button', { name: 'گزینه‌های API طلا' }))
    await user.click(await screen.findByRole('menuitem', { name: 'ویرایش' }))
    const dialog = await screen.findByRole('dialog', { name: 'ویرایش «API طلا»' })
    expect(within(dialog).getByLabelText('مقدار X-Api-Key')).toHaveAttribute('placeholder', '•••••• (ذخیره‌شده)')
    await user.click(within(dialog).getByRole('button', { name: 'آزمایش دریافت' }))
    expect(await within(dialog).findByText('۸۹٬۵۰۰٬۰۰۰')).toBeInTheDocument()
    expect(requests[0]).toMatchObject({ method: 'POST', url: '/admin/price-sources/test?id=2', body: { kind: 'JSON', unit: 'RIAL' } })
    await user.click(within(dialog).getByRole('button', { name: 'ذخیره' }))
    await waitFor(() => expect(requests).toHaveLength(2))
    expect(requests[1]).toMatchObject({ method: 'PUT', url: '/admin/price-sources/2', body: { headers: [{ name: 'X-Api-Key', value: null }] } })
  })

  it('starts a keyed source from a preset and sends the key apart', async () => {
    const { requests, user } = setup()
    renderWithProviders(<PriceSourcesCard />)
    await screen.findByText('API طلا')
    await user.click(screen.getByRole('button', { name: 'منبع جدید' }))
    await user.click(await screen.findByRole('button', { name: /نوسان/ }))
    const dialog = await screen.findByRole('dialog', { name: 'منبع جدید: نوسان' })
    expect(within(dialog).getByText('کلید را از پنل نوسان بگیرید.')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('کلید API'), 'my-key')
    await user.click(within(dialog).getByRole('button', { name: 'ذخیره' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toMatchObject({
      method: 'POST', url: '/admin/price-sources',
      body: { name: 'نوسان', preset: 'NAVASAN', apiKey: 'my-key', url: 'https://api.navasan.tech/latest/?api_key={key}', intervalMinutes: 360 },
    })
  })

  it('reorders sources and shows where each price comes from', async () => {
    const { requests, user } = setup()
    renderWithProviders(<PriceSourcesCard />)
    expect(await screen.findByText('منابع با هم اختلاف دارند.')).toBeInTheDocument()
    expect(screen.getByText(/کنار گذاشته شد/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'ترجیح بیشتر به نوبیتکس' })).toBeDisabled()
    // a held price says so, beside the price still recorded
    expect(screen.getByText('قیمت تازه منتظر تأیید است')).toBeInTheDocument()
    expect(screen.getByText(/منتظر تأیید \|/)).toBeInTheDocument()
    expect(screen.getByText(/قیمت ثبت‌شده:/)).toHaveTextContent('۸٬۹۵۰٬۰۰۰')
    await user.click(screen.getByRole('button', { name: 'ترجیح بیشتر به API طلا' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toMatchObject({ method: 'PUT', url: '/admin/price-sources/order', body: { ids: [2, 1] } })
    // the source is first now: its up arrow is off, so the focus moves to its down arrow, and the move is announced
    await waitFor(() => expect(screen.getByRole('button', { name: 'ترجیح کمتر به API طلا' })).toHaveFocus())
    expect(screen.getByText('«API طلا» اکنون در اولویت ۱ است.')).toBeInTheDocument()
  })

  it('says when a fetch recorded nothing', async () => {
    const info = vi.spyOn(toast, 'info')
    const { user } = setup()
    renderWithProviders(<PriceSourcesCard />)
    await user.click(await screen.findByRole('button', { name: 'دریافت حالا از API طلا' }))
    await waitFor(() => expect(info).toHaveBeenCalledWith(expect.stringContaining('قیمت ثبت‌شده‌ای تغییر نکرد')))
  })

  it('points at the key field when the server asks for the key', async () => {
    const { user } = setup()
    renderWithProviders(<PriceSourcesCard />)
    await screen.findByText('API طلا')
    await user.click(screen.getByRole('button', { name: 'منبع جدید' }))
    await user.click(await screen.findByRole('button', { name: /نوسان/ }))
    const dialog = await screen.findByRole('dialog', { name: 'منبع جدید: نوسان' })
    await user.click(within(dialog).getByRole('button', { name: 'ذخیره' }))
    expect(await within(dialog).findByText('این منبع کلید API می‌خواهد؛ کلید را وارد کنید.')).toBeInTheDocument()
    await waitFor(() => expect(within(dialog).getByLabelText('کلید API')).toHaveFocus())
  })
})
