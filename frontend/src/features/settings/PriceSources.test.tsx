import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Commodity, PriceSource } from '@/lib/api/types'
import { authStatus, COMMODITIES } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { PriceSourcesCard } from './PriceSources'

const GOLD: Commodity = { code: 'GOLD18', nameFa: 'طلای ۱۸ عیار', unitFa: 'گرم', kind: 'GOLD', scale: 3, custom: false, latestPrice: null }
const USDT: Commodity = { code: 'USDT', nameFa: 'تتر', unitFa: 'تتر', kind: 'CRYPTO', scale: 2, custom: false, latestPrice: null }

const NOBITEX: PriceSource = {
  id: 1, name: 'نوبیتکس', kind: 'NOBITEX', url: 'https://apiv2.nobitex.ir', unit: 'RIAL', headers: [],
  mappings: [{ commodity: 'USDT', path: 'usdt', multiplier: null }], intervalMinutes: 30, enabled: false,
  lastRunAt: null, lastSuccessAt: null, lastError: null, lastCount: null, nextRunAt: null,
}
const GOLD_API: PriceSource = {
  id: 2, name: 'API طلا', kind: 'JSON', url: 'https://example.com/gold.json', unit: 'RIAL', headers: [{ name: 'X-Api-Key', hasValue: true }],
  mappings: [{ commodity: 'GOLD18', path: '/data/gold18', multiplier: null }], intervalMinutes: 60, enabled: true,
  lastRunAt: '2026-10-06T08:00:00Z', lastSuccessAt: '2026-10-06T07:00:00Z', lastError: 'سرویس با کد HTTP ۵۰۳ پاسخ داد.', lastCount: 0,
  nextRunAt: '2026-10-06T09:00:00Z',
}

function setup() {
  const requests: { method: string; url: string; body?: unknown }[] = []
  stubApi((url, init) => {
    const method = init.method ?? 'GET'
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/commodities') return { body: [...COMMODITIES, GOLD, USDT] }
    if (method !== 'GET') {
      requests.push({ method, url, body: init.body ? JSON.parse(String(init.body)) : undefined })
      if (url.startsWith('/admin/price-sources/test')) {
        return { body: { error: null, recorded: 0, results: [{ commodity: 'GOLD18', path: '/data/gold18', raw: '89500000', priceToman: '8950000', error: null }] } }
      }
      return { body: GOLD_API }
    }
    if (url === '/admin/price-sources') return { body: [NOBITEX, GOLD_API] }
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
})
