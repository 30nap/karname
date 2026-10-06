import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { authStatus, COMMODITIES, makeAccount } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import type { Commodity } from '@/lib/api/types'
import { AssetsPage } from './AssetsPage'

const ALL: Commodity[] = [
  ...COMMODITIES,
  { code: 'EUR', nameFa: 'یورو', unitFa: 'یورو', kind: 'FIAT', scale: 2, custom: false, latestPrice: null },
  { code: 'GOLD18', nameFa: 'طلای ۱۸ عیار', unitFa: 'گرم', kind: 'GOLD', scale: 3, custom: false, latestPrice: null },
]

function setup(accounts = [makeAccount({ id: 2, name: 'دلار نقد', type: 'CURRENCY', commodity: 'USD', balance: '845' })]) {
  const posted: unknown[] = []
  stubApi((url, init) => {
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/commodities') return { body: ALL }
    if (url.startsWith('/accounts')) return { body: accounts }
    if (url.startsWith('/prices') && init.method === 'POST') {
      posted.push(JSON.parse(String(init.body)))
      return { status: 201, body: {} }
    }
    if (url.startsWith('/prices')) return { body: [] }
  })
  return { posted, user: userEvent.setup() }
}

describe('AssetsPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows the units the user holds, with their quantity and value, and can switch to all units', async () => {
    const { user } = setup()
    renderWithProviders(<AssetsPage />)
    expect(await screen.findByText('دلار آمریکا')).toBeInTheDocument()
    expect(screen.getByText((t) => t.replace(/‎/g, '') === '۸۴۵ دلار')).toBeInTheDocument()
    expect(screen.queryByText('یورو')).not.toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'همه‌ی واحدها' }))
    expect(screen.getByText('یورو')).toBeInTheDocument()
    expect(screen.getByText('طلای ۱۸ عیار')).toBeInTheDocument()
  })

  it('shows every unit when the user holds nothing but Toman', async () => {
    setup([makeAccount({ id: 1 })])
    renderWithProviders(<AssetsPage />)
    expect(await screen.findByText('یورو')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'همه‌ی واحدها' })).toHaveAttribute('aria-checked', 'true')
  })

  it('records a price for an earlier day at noon Tehran time', async () => {
    const { posted, user } = setup()
    renderWithProviders(<AssetsPage />)
    await user.click(await screen.findByText('دلار آمریکا'))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('قیمت هر دلار'), '98000')
    await user.click(within(dialog).getByLabelText('تاریخ قیمت'))
    await user.click(screen.getByRole('button', { name: 'دیروز' }))
    await user.click(within(dialog).getByRole('button', { name: 'ثبت قیمت' }))
    await waitFor(() => expect(posted).toHaveLength(1))
    const yesterday = new Date(Date.now() - 86_400_000).toLocaleDateString('en-CA', { timeZone: 'Asia/Tehran' })
    expect(posted[0]).toEqual({ commodity: 'USD', priceToman: '98000', pricedAt: `${yesterday}T08:30:00Z`, global: false })
  })
})
