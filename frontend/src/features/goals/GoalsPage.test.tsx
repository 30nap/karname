import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Commodity, Goal } from '@/lib/api/types'
import { authStatus, COMMODITIES, makeAccount } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { GoalsPage } from './GoalsPage'

const EUR: Commodity = { code: 'EUR', nameFa: 'یورو', unitFa: 'یورو', kind: 'FIAT', scale: 2, custom: false,
  latestPrice: { priceToman: '120000', pricedAt: '2026-10-06T06:00:00Z', source: 'MANUAL', personal: true, stale: false } }

const GOAL: Goal = {
  id: 7, name: 'صندوق مهاجرت', icon: 'plane', targetAmount: '15000', commodity: 'EUR', targetDate: '2099-09-22', accountIds: [1],
  manualAmount: null, notes: null, archived: false, currentAmount: '2100', currentToman: '252000000', progress: '0.14',
  remaining: '12900', achieved: false, monthlyChange: '91.88', etaMonth: '1417-04', monthsToGoal: 141, monthsLeft: 11,
  requiredPerMonth: '1172.73', requiredPerMonthToman: '140727600', onTrack: false, missingPrices: false,
}

function setup(goals: Goal[] = [GOAL]) {
  const posted: unknown[] = []
  stubApi((url, init) => {
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/commodities') return { body: [...COMMODITIES, EUR] }
    if (url.startsWith('/accounts')) return { body: [makeAccount({ id: 1 }), makeAccount({ id: 9, name: 'وام', type: 'LOAN', liability: true })] }
    if (url === '/goals' && init.method === 'POST') {
      posted.push(JSON.parse(String(init.body)))
      return { status: 201, body: GOAL }
    }
    if (url.startsWith('/goals')) return { body: goals }
  })
  return { posted, user: userEvent.setup() }
}

const plain = (t: string | null | undefined) => (t ?? '').replace(/‎/g, '')

describe('GoalsPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows progress, the required pace and the projected month', async () => {
    setup()
    renderWithProviders(<GoalsPage />)
    expect(await screen.findByText('صندوق مهاجرت')).toBeInTheDocument()
    expect(screen.getByText('عقب از برنامه')).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '14')
    // units come from the commodity list, which loads after the goals
    expect(await screen.findByText((_, el) => el?.tagName === 'SPAN' && plain(el.textContent).startsWith('پس‌انداز لازم: ماهی ۱٬۱۷۲٫۷۳ یورو'))).toBeInTheDocument()
    expect(screen.getByText(/با این روند: تیر ۱۴۱۷ \(۱۱ سال و ۹ ماه دیگر\)/)).toBeInTheDocument()
  })

  it('creates a goal linked to an asset account, never a loan', async () => {
    const { posted, user } = setup([])
    renderWithProviders(<GoalsPage />)
    await user.click(await screen.findByRole('button', { name: 'تعریف اولین هدف' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('نام هدف'), 'سفر')
    await user.type(within(dialog).getByLabelText('مبلغ هدف'), '50000000')
    expect(within(dialog).queryByText('وام')).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'ذخیره' }))
    expect(await within(dialog).findByText(/حداقل یک حساب را انتخاب کنید/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'ذخیره' }))
    await waitFor(() => expect(posted).toHaveLength(1))
    expect(posted[0]).toMatchObject({ name: 'سفر', targetAmount: '50000000', commodity: 'IRT', targetDate: null, accountIds: [1], manualAmount: null })
  })
})
