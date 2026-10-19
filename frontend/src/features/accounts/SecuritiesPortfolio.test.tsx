import { screen, within } from '@testing-library/react'
import type { PortfolioHolding, SecuritiesPortfolio as Portfolio } from '@/lib/api/types'
import { authStatus, COMMODITIES } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { SecuritiesPortfolio } from './SecuritiesPortfolio'

const FOLD: PortfolioHolding = {
  accountId: 4, accountName: 'سهام فولاد', commodity: 'C_FOLD', nameFa: 'فولاد مبارکه اصفهان', symbol: 'فولاد', quantity: '2000',
  averageCostToman: '1255', costBasisToman: '2510000', priceToman: '1300', pricedAt: '2026-10-06T08:00:00Z', priceStale: true,
  marketValueToman: '2600000', unrealizedToman: '90000', realizedToman: '0', costComplete: true,
}

const PORTFOLIO: Portfolio = {
  holdings: [
    FOLD,
    { ...FOLD, accountId: 5, accountName: 'صندوق طلا', commodity: 'C_AYAR', nameFa: 'صندوق عیار', symbol: null, quantity: '100',
      averageCostToman: '60000', costBasisToman: '6000000', priceToman: null, pricedAt: null, priceStale: false,
      marketValueToman: null, unrealizedToman: null },
  ],
  costBasisToman: '2510000', marketValueToman: '2600000', unrealizedToman: '90000', realizedToman: '0', unpriced: 1, costComplete: true,
}

function setup(portfolio: Portfolio) {
  stubApi((url) => {
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/commodities') return { body: COMMODITIES }
    if (url === '/portfolio/securities') return { body: portfolio }
  })
}

const plain = (t: string | null | undefined) => (t ?? '').replace(/[‎‏]/g, '')

describe('SecuritiesPortfolio', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('totals the holdings and shows each one with its gain, linked to its account', async () => {
    setup(PORTFOLIO)
    renderWithProviders(<SecuritiesPortfolio />)
    const card = await screen.findByRole('heading', { name: 'سبد بورس' })
    const region = card.closest('div.rounded-xl') as HTMLElement
    expect(within(region).getByText(/۲ نماد · ۱ نماد بدون قیمت در جمع نیامده/)).toBeInTheDocument()

    const fold = screen.getByRole('link', { name: /فولاد/ })
    expect(fold).toHaveAttribute('href', '/accounts/4')
    expect(plain(fold.textContent)).toContain('۲٬۰۰۰ فولاد مبارکه اصفهان')
    expect(plain(fold.textContent)).toContain('۲٬۶۰۰٬۰۰۰')
    // 90,000 on 2,510,000 paid
    expect(plain(fold.textContent)).toContain('۳٫۶٪')
    expect(within(fold).getByLabelText('قیمت قدیمی')).toBeInTheDocument()

    const fund = screen.getByRole('link', { name: /صندوق عیار/ })
    expect(fund).toHaveAttribute('href', '/accounts/5')
    expect(within(fund).getByText('قیمت ثبت نشده')).toBeInTheDocument()
  })

  it('shows nothing without shares or funds', async () => {
    setup({ ...PORTFOLIO, holdings: [], unpriced: 0 })
    const { container } = renderWithProviders(<SecuritiesPortfolio />)
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(container.textContent).not.toContain('سبد بورس')
  })
})
