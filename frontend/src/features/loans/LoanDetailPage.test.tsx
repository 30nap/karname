import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Installment, Loan } from '@/lib/api/types'
import { authStatus, COMMODITIES, makeAccount } from '@/test/fixtures'
import { renderRoutes, stubApi } from '@/test/utils'
import { addDaysIso, todayIso } from '@/lib/jalali'
import { LoanDetailPage } from './LoanDetailPage'

const today = todayIso()

function installment(number: number, dueDate: string, status: Installment['status'], paid = false): Installment {
  return {
    number, dueDate, amount: '3615240', principal: '2115240', interest: '1500000', balanceAfter: String(100000000 - number * 2115240), status,
    paidOn: paid ? dueDate : null, paidAmount: paid ? '3615240' : null,
  }
}

const LOAN: Loan = {
  id: 3, accountId: 20, name: 'وام خودرو', bank: 'MELLAT', counterparty: null, principal: '100000000', annualRate: '18', termMonths: 4,
  firstDueDate: addDaysIso(today, -40), method: 'ANNUITY', installmentAmount: null, paidBefore: 0, paymentAccountId: 1, notes: null,
  outstanding: '97884760', totalInterest: '6000000', remainingInterest: '4500000', paidCount: 1, overdueCount: 1, overdueAmount: '3615240',
  next: installment(2, addDaysIso(today, -10), 'OVERDUE'), endDate: addDaysIso(today, 35),
  installments: [
    installment(1, addDaysIso(today, -40), 'PAID', true),
    installment(2, addDaysIso(today, -10), 'OVERDUE'),
    installment(3, addDaysIso(today, 5), 'DUE_SOON'),
    installment(4, addDaysIso(today, 35), 'UPCOMING'),
  ],
}

function setup() {
  const requests: { method: string; url: string; body?: unknown }[] = []
  stubApi((url, init) => {
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/commodities') return { body: COMMODITIES }
    if (url.startsWith('/accounts')) return { body: [makeAccount({ id: 1 }), makeAccount({ id: 20, name: 'وام خودرو', type: 'LOAN', liability: true })] }
    if (url === '/loans/3' && (init.method ?? 'GET') === 'GET') return { body: LOAN }
    if (url.startsWith('/loans/3/installments')) {
      requests.push({ method: String(init.method), url, body: init.body ? JSON.parse(String(init.body)) : undefined })
      return { body: LOAN }
    }
  })
  return { requests, user: userEvent.setup() }
}

const rows = () => within(screen.getByRole('list')).getAllByRole('listitem')

describe('LoanDetailPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('summarises the loan and lists unpaid installments first', async () => {
    const { user } = setup()
    renderRoutes([{ path: '/loans/:id', element: <LoanDetailPage /> }], '/loans/3')
    expect(await screen.findByRole('heading', { name: 'وام خودرو' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('۱ قسط به مبلغ')
    expect(screen.getByRole('alert')).toHaveTextContent('از سررسیدش گذشته')
    expect(rows()).toHaveLength(3)
    expect(rows()[0]).toHaveTextContent('معوق')
    expect(rows()[1]).toHaveTextContent('نزدیک سررسید')
    await user.click(screen.getByRole('radio', { name: 'همه (۴)' }))
    expect(rows()).toHaveLength(4)
    expect(rows()[0]).toHaveTextContent('پرداخت‌شده')
  })

  it('pays an installment from the loan’s payment account, on its due date and with a penalty', async () => {
    const { requests, user } = setup()
    renderRoutes([{ path: '/loans/:id', element: <LoanDetailPage /> }], '/loans/3')
    await screen.findByRole('heading', { name: 'وام خودرو' })
    await user.click(within(rows()[0]).getByRole('button', { name: 'پرداخت' }))
    const dialog = await screen.findByRole('dialog', { name: 'پرداخت قسط ۲ وام خودرو' })
    expect(within(dialog).getByRole('combobox', { name: 'پرداخت از حساب' })).toHaveTextContent('کارت ملت')
    await user.click(within(dialog).getByRole('button', { name: /سر موعد پرداخت کردم/ }))
    await user.type(within(dialog).getByLabelText(/جریمه‌ی دیرکرد/), '50000')
    expect(dialog).toHaveTextContent('جمع پرداخت۳٬۶۶۵٬۲۴۰ تومان')
    await user.click(within(dialog).getByRole('button', { name: 'ثبت پرداخت' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toEqual({
      method: 'POST', url: '/loans/3/installments/2/payment', body: { accountId: 1, date: addDaysIso(today, -10), penalty: '50000' },
    })
  })

  it('undoes a recorded payment after confirmation', async () => {
    const { requests, user } = setup()
    renderRoutes([{ path: '/loans/:id', element: <LoanDetailPage /> }], '/loans/3')
    await screen.findByRole('heading', { name: 'وام خودرو' })
    await user.click(screen.getByRole('radio', { name: 'همه (۴)' }))
    await user.click(screen.getByRole('button', { name: 'گزینه‌های قسط ۱' }))
    await user.click(await screen.findByRole('menuitem', { name: 'برگرداندن پرداخت' }))
    const confirm = await screen.findByRole('alertdialog', { name: 'برگرداندن پرداخت قسط ۱؟' })
    await user.click(within(confirm).getByRole('button', { name: 'برگرداندن' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toMatchObject({ method: 'DELETE', url: '/loans/3/installments/1/payment' })
  })
})
