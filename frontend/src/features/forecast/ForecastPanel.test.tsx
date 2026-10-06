import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Forecast } from '@/lib/api/types'
import { authStatus, COMMODITIES } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { addDaysIso, todayIso } from '@/lib/jalali'
import { ForecastPanel } from './ForecastPanel'

const today = todayIso()
const d = (days: number) => addDaysIso(today, days)

const FORECAST: Forecast = {
  from: today, to: d(90), startBalance: '20000000', endBalance: '41000000', minBalance: '-3000000', minDate: d(9),
  inflow: '60000000', outflow: '39000000',
  events: [
    { date: today, source: 'RECURRING', title: 'اجاره', amount: '-18000000', overdue: true, link: '/recurring' },
    { date: d(9), source: 'CHEQUE', title: 'چک صادره به فروشگاه', amount: '-5000000', overdue: false, link: '/cheques' },
    { date: d(17), source: 'RECURRING', title: 'حقوق', amount: '60000000', overdue: false, link: '/recurring' },
    { date: d(20), source: 'LOAN', title: 'قسط ۲ وام خودرو', amount: '-16000000', overdue: false, link: '/loans/3' },
  ],
  points: [
    { date: today, balance: '2000000' },
    { date: d(9), balance: '-3000000' },
    { date: d(17), balance: '57000000' },
    { date: d(20), balance: '41000000' },
    { date: d(90), balance: '41000000' },
  ],
}

function setup() {
  const fetch = stubApi((url) => {
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/commodities') return { body: COMMODITIES }
    if (url.startsWith('/forecast')) return { body: FORECAST }
  })
  return { fetch, user: userEvent.setup() }
}

describe('ForecastPanel', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('warns before cash runs out and lists what drives it', async () => {
    setup()
    renderWithProviders(<ForecastPanel />)
    expect(await screen.findByRole('alert')).toHaveTextContent('به ‎−۳٬۰۰۰٬۰۰۰ تومان برسد')
    expect(screen.getByText('موجودی نقد امروز').parentElement).toHaveTextContent('۲۰٬۰۰۰٬۰۰۰ تومان')
    expect(screen.getByRole('link', { name: /اجاره/ })).toHaveTextContent('عقب‌افتاده')
    expect(screen.getByRole('link', { name: /قسط ۲ وام خودرو/ })).toHaveAttribute('href', '/loans/3')
    expect(screen.getByRole('link', { name: /حقوق/ })).toHaveTextContent('+۶۰٬۰۰۰٬۰۰۰ تومان')
  })

  it('has a table view with the change on each day', async () => {
    const { user } = setup()
    renderWithProviders(<ForecastPanel />)
    await screen.findByRole('alert')
    await user.click(screen.getByRole('radio', { name: 'نمایش جدول' }))
    const table = screen.getByRole('table')
    const body = within(table).getAllByRole('row').slice(1)
    expect(body).toHaveLength(5)
    // today: 20M at hand minus the overdue rent
    expect(body[0]).toHaveTextContent('‎−۱۸٬۰۰۰٬۰۰۰')
    expect(body[0]).toHaveTextContent('۲٬۰۰۰٬۰۰۰ تومان')
    expect(body[2]).toHaveTextContent('۶۰٬۰۰۰٬۰۰۰')
    expect(body[4]).toHaveTextContent('—')
  })

  it('asks for a longer horizon on demand', async () => {
    const { fetch, user } = setup()
    renderWithProviders(<ForecastPanel />)
    await screen.findByRole('alert')
    await user.click(screen.getByRole('radio', { name: '۳۰ روز' }))
    expect(fetch.mock.calls.map(([u]) => String(u))).toContain('/api/v1/forecast?days=30')
  })
})
