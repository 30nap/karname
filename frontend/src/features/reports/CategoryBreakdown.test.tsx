import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { CategoryReport } from '@/lib/api/types'
import { authStatus } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { CategoryBreakdown } from './CategoryBreakdown'

const REPORT: CategoryReport = {
  fromMonth: '1405-07', toMonth: '1405-07', kind: 'EXPENSE', totalToman: '7500000', previousTotalToman: '5000000', unpricedCount: 0,
  items: [
    { categoryId: 10, name: 'خوراک و خواربار', icon: 'shopping-cart', valueToman: '3500000', share: '0.4667', count: 1,
      previousToman: '4000000', averageToman: '2666667', children: [] },
    { categoryId: 20, name: 'رستوران و کافه', icon: 'utensils', valueToman: '3000000', share: '0.4', count: 2, previousToman: '1000000',
      averageToman: '333333', children: [
        { categoryId: 21, name: 'رستوران', icon: 'utensils', valueToman: '2000000', share: '0.2667', count: 1, previousToman: '1000000', averageToman: null, children: [] },
      ] },
    { categoryId: null, name: null, icon: null, valueToman: '1000000', share: '0.1333', count: 1, previousToman: '0', averageToman: '0', children: [] },
  ],
}

describe('CategoryBreakdown', () => {
  beforeEach(() => stubApi((url) => (url === '/auth/status' ? { body: authStatus() } : undefined)))
  afterEach(() => vi.unstubAllGlobals())

  it('ranks categories with shares, changes and links to their transactions', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CategoryBreakdown report={REPORT} isPending={false} fromMonth="1405-07" toMonth="1405-07" />)
    const food = await screen.findByRole('link', { name: 'خوراک و خواربار' })
    expect(food).toHaveAttribute('href', '/transactions?type=EXPENSE&from=2026-09-23&to=2026-10-22&category=10')
    expect(screen.getByRole('link', { name: 'بدون دسته‌بندی' })).toHaveAttribute('href', expect.stringContaining('uncategorized=1'))
    expect(screen.getByText('۴۶٫۷٪')).toBeInTheDocument()
    expect(screen.getByLabelText('کاهش').parentElement).toHaveTextContent('۱۳٪')
    expect(screen.getByLabelText('افزایش').parentElement).toHaveTextContent('۲۰۰٪')
    expect(screen.getByText('جدید')).toBeInTheDocument()

    expect(screen.queryByRole('link', { name: 'رستوران' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'زیرمجموعه‌های رستوران و کافه' }))
    expect(screen.getByRole('link', { name: 'رستوران' })).toBeInTheDocument()
  })
})
