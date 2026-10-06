import { screen } from '@testing-library/react'
import type { Transaction } from '@/lib/api/types'
import { authStatus, COMMODITIES } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { TransactionList } from './TransactionList'

const mellat = { id: 1, name: 'ملت حقوق', commodity: 'IRT', type: 'BANK' as const }
const dollars = { id: 2, name: 'دلار نقد', commodity: 'USD', type: 'CURRENCY' as const }

function transfer(overrides: Partial<Transaction> = {}): Transaction {
  return {
    id: 10, type: 'TRANSFER', date: '2026-04-30', account: mellat, amount: '25350000', toAccount: dollars, toAmount: '300',
    fee: null, category: null, description: null, notes: null, tags: [], source: 'MANUAL', createdAt: '2026-04-30T10:00:00Z',
    ...overrides,
  }
}

/** Matches text ignoring the LRM that keeps signs on the left of the digits. */
const text = (expected: string) => (content: string) => content.replace(/‎/g, '') === expected

describe('TransactionList', () => {
  beforeEach(() => stubApi((url) => {
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/commodities') return { body: COMMODITIES }
  }))
  afterEach(() => vi.unstubAllGlobals())

  it('shows an exchange as money coming into the viewed account', async () => {
    renderWithProviders(<TransactionList items={[transfer()]} perspective={2} />)
    expect(await screen.findByText(text('+۳۰۰ دلار'))).toBeInTheDocument()
    expect(screen.getByText('انتقال از ملت حقوق')).toBeInTheDocument()
    expect(screen.getByText(text('۲۵٬۳۵۰٬۰۰۰ تومان'))).toBeInTheDocument()
  })

  it('shows the same exchange as money leaving the source account', async () => {
    renderWithProviders(<TransactionList items={[transfer({ fee: '5000' })]} perspective={1} />)
    expect(await screen.findByText(text('−۲۵٬۳۵۰٬۰۰۰ تومان'))).toBeInTheDocument()
    expect(screen.getByText('انتقال به دلار نقد')).toBeInTheDocument()
    expect(screen.getByText(text('۵٬۰۰۰ تومان'))).toBeInTheDocument()
  })

  it('adds the year to day headings outside the current year', async () => {
    renderWithProviders(<TransactionList items={[transfer({ date: '2025-03-15' })]} />)
    expect(await screen.findByRole('heading', { name: 'شنبه ۲۵ اسفند ۱۴۰۳' })).toBeInTheDocument()
  })
})
