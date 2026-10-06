import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { BudgetMonth } from '@/lib/api/types'
import { currentMonthKey } from '@/lib/jalali'
import { authStatus, CATEGORIES } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { BudgetsPage } from './BudgetsPage'

const month = currentMonthKey()

const MONTH: BudgetMonth = {
  month, daysInMonth: 30, daysElapsed: 14, current: true,
  totalBudget: '8000000', totalSpent: '8100000', unbudgetedSpent: '3000000', totalExpense: '11100000',
  items: [
    { categoryId: 1, name: 'خوراک', icon: 'utensils', parentId: null, parentName: null, amount: '5000000', spent: '5100000',
      remaining: '-100000', ratio: '1.02', status: 'OVER', recurring: true, since: month, projected: '10928571', unpricedCount: 0 },
    { categoryId: 3, name: 'حمل‌ونقل', icon: 'car', parentId: null, parentName: null, amount: '3000000', spent: '2500000',
      remaining: '500000', ratio: '0.8333', status: 'WARNING', recurring: false, since: month, projected: null, unpricedCount: 0 },
  ],
}

function setup(data: BudgetMonth = MONTH) {
  const requests: { method: string; url: string; body?: unknown }[] = []
  stubApi((url, init) => {
    const method = init.method ?? 'GET'
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/categories') return { body: CATEGORIES }
    if (url.startsWith('/budgets/suggestions')) {
      return { body: [{ categoryId: 3, name: 'حمل‌ونقل', icon: 'car', averageToman: '2400000', suggestedToman: '2400000', currentBudget: null }] }
    }
    if (url.startsWith('/budgets') && method === 'GET') return { body: data }
    if (url.startsWith('/budgets')) {
      requests.push({ method, url, body: init.body ? JSON.parse(String(init.body)) : undefined })
      return { body: data }
    }
  })
  return { requests, user: userEvent.setup() }
}

const plain = (t: string) => t.replace(/‎/g, '')

describe('BudgetsPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows each budget with its status, projection and the month total', async () => {
    setup()
    renderWithProviders(<BudgetsPage />)
    expect(await screen.findByText('خوراک')).toBeInTheDocument()
    expect(screen.getByText((_, el) => el?.tagName === 'SPAN' && plain(el.textContent ?? '') === '۱۰۰٬۰۰۰ تومان بیشتر از بودجه')).toBeInTheDocument()
    expect(screen.getByText(/نزدیک سقف/)).toBeInTheDocument()
    expect(screen.getByText('فقط این ماه')).toBeInTheDocument()
    expect(screen.getByText(/پیش‌بینی تا آخر ماه/)).toBeInTheDocument()
    expect(screen.getByText('۱۶ روز تا پایان ماه')).toBeInTheDocument()
    expect(screen.getAllByRole('progressbar')[0]).toHaveAttribute('aria-valuenow', '100')
  })

  it('removes a budget for this month only', async () => {
    const { requests, user } = setup()
    renderWithProviders(<BudgetsPage />)
    await user.click(await screen.findByRole('button', { name: 'گزینه‌های بودجه‌ی خوراک' }))
    await user.click(await screen.findByRole('menuitem', { name: 'حذف' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: /^فقط/ }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toMatchObject({ method: 'DELETE', url: `/budgets/${month}/1?scope=MONTH` })
  })

  it('applies suggested budgets for categories without one', async () => {
    const { requests, user } = setup({ ...MONTH, items: [] })
    renderWithProviders(<BudgetsPage />)
    await user.click((await screen.findAllByRole('button', { name: 'پیشنهاد بودجه' }))[0])
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText('حمل‌ونقل')
    await user.click(within(dialog).getByRole('button', { name: 'ثبت ۱ بودجه' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toEqual({ method: 'PUT', url: `/budgets/${month}/3`, body: { amount: '2400000', recurring: true } })
  })
})
