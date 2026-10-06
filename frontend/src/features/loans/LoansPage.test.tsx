import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Loan } from '@/lib/api/types'
import { authStatus, COMMODITIES, makeAccount } from '@/test/fixtures'
import { renderRoutes, stubApi } from '@/test/utils'
import { addDaysIso, todayIso } from '@/lib/jalali'
import { LoansPage } from './LoansPage'

const today = todayIso()

const CAR: Loan = {
  id: 3, accountId: 20, name: 'وام خودرو', bank: 'MELLAT', counterparty: null, principal: '100000000', annualRate: '18', termMonths: 36,
  firstDueDate: addDaysIso(today, -40), method: 'ANNUITY', installmentAmount: null, paidBefore: 0, paymentAccountId: 1, notes: null,
  outstanding: '97884760', totalInterest: '30148620', remainingInterest: '28648620', paidCount: 1, overdueCount: 1, overdueAmount: '3615240',
  next: { number: 2, dueDate: addDaysIso(today, -10), amount: '3615240', principal: '2147000', interest: '1468240', balanceAfter: '95737760',
    status: 'OVERDUE', paidOn: null, paidAmount: null },
  endDate: addDaysIso(today, 1020), installments: null,
}

const QARZ: Loan = {
  ...CAR, id: 4, accountId: 21, name: 'قرض‌الحسنه', bank: null, principal: '12000000', annualRate: '0', termMonths: 12, outstanding: '0',
  totalInterest: '0', remainingInterest: '0', paidCount: 12, overdueCount: 0, overdueAmount: '0', next: null,
}

function setup(loans: Loan[]) {
  const posted: unknown[] = []
  stubApi((url, init) => {
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/commodities') return { body: COMMODITIES }
    if (url.startsWith('/accounts')) return { body: [makeAccount({ id: 1 }), makeAccount({ id: 5, name: 'دلار', type: 'CURRENCY', commodity: 'USD' })] }
    if (url.startsWith('/loans/preview')) {
      return { body: { firstInstallment: '3615240', lastInstallment: '3615251', totalInterest: '30148620', totalPaid: '130148620', endDate: '2029-09-22' } }
    }
    if (url === '/loans' && init.method === 'POST') {
      posted.push(JSON.parse(String(init.body)))
      return { status: 201, body: { ...CAR, id: 9 } }
    }
    if (url === '/loans') return { body: loans }
  })
  return { posted, user: userEvent.setup() }
}

describe('LoansPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('totals the loans and flags overdue installments', async () => {
    setup([CAR, QARZ])
    renderRoutes([{ path: '/', element: <LoansPage /> }])
    expect(await screen.findByRole('alert')).toHaveTextContent('۱ قسط معوق به مبلغ ۳٬۶۱۵٬۲۴۰ تومان دارید در وام خودرو.')
    expect(within(screen.getByRole('alert')).getByRole('link', { name: 'وام خودرو' })).toHaveAttribute('href', '/loans/3')
    expect(screen.getByText('مانده‌ی کل وام‌ها').parentElement).toHaveTextContent('۹۷٬۸۸۴٬۷۶۰ تومان')
    expect(screen.getByText('اقساط ماهانه').parentElement).toHaveTextContent('۱ وام فعال')
    expect(screen.getByText('تسویه‌شده')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /قرض‌الحسنه/ })).toHaveAttribute('href', '/loans/4')
  })

  it('records a newly received loan into the everyday account and opens it', async () => {
    const { posted, user } = setup([])
    const { router } = renderRoutes([{ path: '/', element: <LoansPage /> }, { path: '/loans/:id', element: <p>loan page</p> }])
    await user.click(await screen.findByRole('button', { name: 'ثبت اولین وام' }))
    const dialog = await screen.findByRole('dialog', { name: 'وام جدید' })
    await user.type(within(dialog).getByLabelText('نام'), 'وام خودرو')
    await user.type(within(dialog).getByLabelText('مبلغ وام'), '100000000')
    // the schedule preview arrives after a short pause in typing
    expect(await within(dialog).findByText('قسط ماهانه')).toBeInTheDocument()
    expect(within(dialog).getByText('قسط ماهانه').parentElement).toHaveTextContent('۳٬۶۱۵٬۲۴۰ تومان')
    expect(within(dialog).getByRole('combobox', { name: 'واریز به حساب' })).toHaveTextContent('کارت ملت')
    await user.click(within(dialog).getByRole('button', { name: 'ذخیره' }))
    await waitFor(() => expect(posted).toHaveLength(1))
    expect(posted[0]).toMatchObject({
      name: 'وام خودرو', principal: '100000000', annualRate: '18', termMonths: 36, method: 'ANNUITY', start: 'NEW',
      depositAccountId: 1, paymentAccountId: 1, receivedOn: today, paidBefore: 0,
    })
    await waitFor(() => expect(router.state.location.pathname).toBe('/loans/9'))
  })
})
