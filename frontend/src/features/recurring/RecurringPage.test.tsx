import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Occurrence, RecurringRule } from '@/lib/api/types'
import { authStatus, CATEGORIES, COMMODITIES, makeAccount } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { addDaysIso, toJalali, todayIso } from '@/lib/jalali'
import { RecurringPage } from './RecurringPage'

const today = todayIso()
const due = addDaysIso(today, -3)

const RENT: RecurringRule = {
  id: 1, name: 'اجاره', type: 'EXPENSE', accountId: 1, toAccountId: null, amount: '18000000', toAmount: null, categoryId: null, description: null,
  frequency: 'MONTHLY', interval: 1, dayOfMonth: 3, dayOfWeek: null, monthOfYear: null, startDate: '2026-03-23', endDate: null, mode: 'REMIND',
  active: true, nextDate: addDaysIso(today, 27), lastPosted: null, dueCount: 1,
}
const SALARY: RecurringRule = {
  ...RENT, id: 2, name: 'حقوق', type: 'INCOME', amount: '60000000', categoryId: 6, dayOfMonth: 1, mode: 'AUTO', dueCount: 0,
}
const PENDING: Occurrence = {
  ruleId: 1, name: 'اجاره', type: 'EXPENSE', mode: 'REMIND', date: due, amount: '18000000', toAmount: null, accountId: 1, toAccountId: null,
  categoryId: null, status: 'DUE', transactionId: null,
}

function setup(rules: RecurringRule[] = [RENT, SALARY]) {
  const requests: { method: string; url: string; body?: unknown }[] = []
  stubApi((url, init) => {
    const method = init.method ?? 'GET'
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/commodities') return { body: COMMODITIES }
    if (url === '/categories') return { body: CATEGORIES }
    if (url.startsWith('/accounts')) return { body: [makeAccount({ id: 1 }), makeAccount({ id: 5, name: 'دلار', type: 'CURRENCY', commodity: 'USD' })] }
    if (method !== 'GET') {
      requests.push({ method, url, body: init.body ? JSON.parse(String(init.body)) : undefined })
      return url.endsWith('/skip') ? { status: 204 } : { body: url.endsWith('/post') ? { transactionId: 99 } : RENT }
    }
    if (url === '/recurring/pending') return { body: rules.length ? [PENDING] : [] }
    if (url === '/recurring') return { body: rules }
  })
  return { requests, user: userEvent.setup() }
}

describe('RecurringPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('lists due occurrences and the rules with their Jalali schedule', async () => {
    setup()
    renderWithProviders(<RecurringPage />)
    const pending = await screen.findByRole('heading', { name: 'منتظر ثبت' })
    const pendingCard = pending.closest('div.rounded-xl') as HTMLElement
    expect(within(pendingCard).getByText('سررسید شده')).toBeInTheDocument()
    expect(within(pendingCard).getByText(/۳ روز پیش/)).toBeInTheDocument()
    // account names arrive with the account list
    expect(await screen.findByText(/ماهانه، روز ۳ \| کارت ملت/)).toBeInTheDocument()
    expect(screen.getByText(/ماهانه، روز ۱ \| کارت ملت/)).toBeInTheDocument()
    expect(screen.getAllByText('خودکار')).toHaveLength(1)
    expect(screen.getByText(/۱ نوبت ثبت‌نشده/)).toBeInTheDocument()
  })

  it('posts a due occurrence as scheduled, or skips it', async () => {
    const { requests, user } = setup()
    renderWithProviders(<RecurringPage />)
    await screen.findByRole('heading', { name: 'منتظر ثبت' })
    await user.click(screen.getByRole('button', { name: 'ثبت' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toEqual({ method: 'POST', url: `/recurring/1/occurrences/${due}/post`, body: {} })

    await user.click(screen.getAllByRole('button', { name: 'گزینه‌های اجاره' })[0])
    await user.click(await screen.findByRole('menuitem', { name: 'رد کردن این نوبت' }))
    await waitFor(() => expect(requests).toHaveLength(2))
    expect(requests[1]).toMatchObject({ method: 'POST', url: `/recurring/1/occurrences/${due}/skip` })
  })

  it('pauses a rule by saving it whole', async () => {
    const { requests, user } = setup()
    renderWithProviders(<RecurringPage />)
    await screen.findByText('حقوق')
    await user.click(screen.getByRole('button', { name: 'گزینه‌های حقوق' }))
    await user.click(await screen.findByRole('menuitem', { name: 'توقف' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toEqual({
      method: 'PUT', url: '/recurring/2',
      body: {
        name: 'حقوق', type: 'INCOME', accountId: 1, toAccountId: null, amount: '60000000', toAmount: null, categoryId: 6, description: null,
        frequency: 'MONTHLY', interval: 1, dayOfMonth: 1, dayOfWeek: null, monthOfYear: null, startDate: '2026-03-23', endDate: null,
        mode: 'AUTO', active: false,
      },
    })
  })

  it('creates an automatic monthly rule on today’s Jalali day from the everyday account', async () => {
    const { requests, user } = setup([])
    renderWithProviders(<RecurringPage />)
    await user.click(await screen.findByRole('button', { name: 'تعریف اولین تراکنش تکراری' }))
    const dialog = await screen.findByRole('dialog', { name: 'تراکنش تکراری جدید' })
    await user.click(within(dialog).getByRole('button', { name: 'ذخیره' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('یک نام برای این تراکنش تکراری بنویسید')
    await user.type(within(dialog).getByLabelText('نام'), 'اینترنت')
    await user.type(within(dialog).getByLabelText('مبلغ'), '500000')
    expect(within(dialog).getByRole('combobox', { name: 'از حساب' })).toHaveTextContent('کارت ملت')
    await user.click(within(dialog).getByRole('radio', { name: 'خودکار' }))
    await user.click(within(dialog).getByRole('button', { name: 'ذخیره' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toEqual({
      method: 'POST', url: '/recurring',
      body: {
        name: 'اینترنت', type: 'EXPENSE', accountId: 1, toAccountId: null, amount: '500000', toAmount: null, categoryId: null, description: null,
        frequency: 'MONTHLY', interval: 1, dayOfMonth: toJalali(today).day, dayOfWeek: null, monthOfYear: null, startDate: today, endDate: null,
        mode: 'AUTO', active: true,
      },
    })
  })
})
