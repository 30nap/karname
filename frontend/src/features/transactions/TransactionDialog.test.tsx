import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { todayIso } from '@/lib/jalali'
import type { Me } from '@/lib/api/types'
import { authStatus, CATEGORIES, COMMODITIES, makeAccount, makeMe } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { TransactionDialogProvider, useTransactionDialog, type TransactionDraft } from './TransactionDialog'

const ACCOUNTS = [
  makeAccount({ id: 1, name: 'کارت ملت', commodity: 'IRT' }),
  makeAccount({ id: 2, name: 'دلار نقد', type: 'CURRENCY', commodity: 'USD', bank: null, balance: '100', valueToman: '10000000' }),
]

function Opener({ draft }: { draft?: TransactionDraft }) {
  const open = useTransactionDialog()
  return <button type="button" onClick={() => open({ draft })}>باز کردن</button>
}

function setup(me: Me = makeMe(), draft?: TransactionDraft) {
  const posted: unknown[] = []
  stubApi((url, init) => {
    if (url === '/auth/status') return { body: authStatus(me) }
    if (url.startsWith('/accounts')) return { body: ACCOUNTS }
    if (url === '/commodities') return { body: COMMODITIES }
    if (url === '/categories') return { body: CATEGORIES }
    if (url.startsWith('/categories/suggest')) return { body: { categoryId: url.includes(encodeURIComponent('اسنپ')) ? 4 : null } }
    if (url === '/transactions' && init.method === 'POST') {
      posted.push(JSON.parse(String(init.body)))
      return { status: 201, body: { id: 99 } }
    }
    if (url.startsWith('/transactions') || url.startsWith('/dashboard') || url.startsWith('/net-worth')) return { body: {} }
  })
  const user = userEvent.setup()
  renderWithProviders(<TransactionDialogProvider><Opener draft={draft} /></TransactionDialogProvider>)
  return { posted, user }
}

describe('TransactionDialog', () => {
  beforeEach(() => localStorage.setItem('karname.lastAccount', '1'))
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('records an expense with the last used account and a learned category', async () => {
    const { posted, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'باز کردن' }))
    await user.type(await screen.findByLabelText('مبلغ'), '185000')
    await user.type(screen.getByLabelText(/شرح/), 'اسنپ')
    // The merchant rule suggestion fills in the category after a short pause.
    await waitFor(() => expect(screen.getByLabelText('دسته‌بندی')).toHaveTextContent('تاکسی اینترنتی'))
    await user.click(screen.getByRole('button', { name: 'ثبت' }))
    await waitFor(() => expect(posted).toHaveLength(1))
    expect(posted[0]).toEqual({
      type: 'EXPENSE', date: todayIso(), accountId: 1, amount: '185000', toAccountId: null, toAmount: null, fee: null,
      categoryId: 4, description: 'اسنپ', notes: null, tags: [],
    })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('converts amounts typed in Rial to Toman', async () => {
    const { posted, user } = setup(makeMe({ displayUnit: 'RIAL' }))
    await user.click(await screen.findByRole('button', { name: 'باز کردن' }))
    await user.type(await screen.findByLabelText('مبلغ'), '2500000')
    await user.click(screen.getByRole('button', { name: 'ثبت' }))
    await waitFor(() => expect(posted).toHaveLength(1))
    expect(posted[0]).toMatchObject({ type: 'EXPENSE', amount: '250000' })
  })

  it('records a currency purchase as an exchange with both amounts and shows the implied rate', async () => {
    const { posted, user } = setup(makeMe(), { type: 'TRANSFER', accountId: 1, toAccountId: 2 })
    await user.click(await screen.findByRole('button', { name: 'باز کردن' }))
    await user.type(await screen.findByLabelText('مقدار پرداختی'), '1000000')
    await user.type(screen.getByLabelText('مقدار دریافتی'), '10')
    expect(screen.getByText('هر دلار ≈ ۱۰۰٬۰۰۰ تومان')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'ثبت' }))
    await waitFor(() => expect(posted).toHaveLength(1))
    expect(posted[0]).toMatchObject({ type: 'TRANSFER', accountId: 1, amount: '1000000', toAccountId: 2, toAmount: '10', categoryId: null })
  })

  it('falls back to the first everyday Toman account when the remembered one is gone', async () => {
    localStorage.setItem('karname.lastAccount', '999')
    const { posted, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'باز کردن' }))
    await waitFor(() => expect(screen.getByLabelText('از حساب')).toHaveTextContent('کارت ملت'))
    await user.type(screen.getByLabelText('مبلغ'), '1000')
    await user.click(screen.getByRole('button', { name: 'ثبت' }))
    await waitFor(() => expect(posted).toHaveLength(1))
    expect(posted[0]).toMatchObject({ accountId: 1 })
  })

  it('validates the amount before saving', async () => {
    const { posted, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'باز کردن' }))
    await screen.findByLabelText('مبلغ')
    await user.click(screen.getByRole('button', { name: 'ثبت' }))
    expect(await screen.findByText('مبلغ را وارد کنید.')).toBeInTheDocument()
    expect(posted).toHaveLength(0)
  })

  it('keeps the dialog open for the next entry with «ثبت و بعدی»', async () => {
    const { posted, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'باز کردن' }))
    const amount = await screen.findByLabelText('مبلغ')
    await user.type(amount, '50000')
    await user.click(screen.getByRole('button', { name: 'ثبت و بعدی' }))
    await waitFor(() => expect(posted).toHaveLength(1))
    await waitFor(() => expect(screen.getByLabelText('مبلغ')).toHaveValue(''))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})
