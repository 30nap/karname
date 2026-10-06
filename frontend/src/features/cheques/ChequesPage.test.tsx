import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Cheque } from '@/lib/api/types'
import { authStatus, CATEGORIES, COMMODITIES, makeAccount } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { addDaysIso, todayIso } from '@/lib/jalali'
import { ChequesPage } from './ChequesPage'

const today = todayIso()

const base: Cheque = {
  id: 1, direction: 'ISSUED', status: 'PENDING', sayadId: '1234567890123456', serial: null, bank: 'MELLAT', accountId: 1, counterAccountId: null,
  categoryId: null, counterparty: 'آقای رضایی', amount: '18000000', issueDate: null, dueDate: addDaysIso(today, -2), settledOn: null,
  description: null, notes: null, transactionId: null, overdue: true, daysToDue: -2,
}
const CHEQUES: Cheque[] = [
  base,
  { ...base, id: 2, sayadId: null, bank: null, accountId: null, counterparty: 'فروشگاه', amount: '5000000', dueDate: addDaysIso(today, 10), overdue: false, daysToDue: 10 },
  { ...base, id: 3, direction: 'RECEIVED', sayadId: null, counterparty: 'علی', amount: '5000000', dueDate: addDaysIso(today, 5), overdue: false, daysToDue: 5 },
  { ...base, id: 4, status: 'CLEARED', sayadId: null, counterparty: 'تعمیرگاه', amount: '2000000', dueDate: addDaysIso(today, -20),
    settledOn: addDaysIso(today, -20), overdue: false, daysToDue: -20, transactionId: 50 },
]

function setup(cheques: Cheque[] = CHEQUES) {
  const requests: { method: string; url: string; body?: unknown }[] = []
  stubApi((url, init) => {
    const method = init.method ?? 'GET'
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/commodities') return { body: COMMODITIES }
    if (url === '/categories') return { body: CATEGORIES }
    if (url.startsWith('/accounts')) return { body: [makeAccount({ id: 1 })] }
    if (method !== 'GET') {
      requests.push({ method, url, body: init.body ? JSON.parse(String(init.body)) : undefined })
      return { status: method === 'POST' && url === '/cheques' ? 201 : 200, body: base }
    }
    if (url === '/cheques') return { body: cheques }
  })
  return { requests, user: userEvent.setup() }
}

const rows = () => within(screen.getAllByRole('list').at(-1)!).getAllByRole('listitem')

describe('ChequesPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('totals pending cheques, flags overdue ones and filters by direction and status', async () => {
    const { user } = setup()
    renderWithProviders(<ChequesPage />)
    expect(await screen.findByText('چک‌های صادره‌ی پاس‌نشده')).toBeInTheDocument()
    expect(screen.getByText('چک‌های صادره‌ی پاس‌نشده').parentElement).toHaveTextContent('۲۳٬۰۰۰٬۰۰۰ تومان')
    expect(screen.getByRole('alert')).toHaveTextContent('سررسید ۱ چک گذشته')
    expect(rows()).toHaveLength(2)
    expect(rows()[0]).toHaveTextContent('سررسید گذشته')
    expect(rows()[0]).toHaveTextContent('۱۲۳۴ ۵۶۷۸ ۹۰۱۲ ۳۴۵۶')
    await user.click(screen.getByRole('radio', { name: 'همه' }))
    expect(rows()).toHaveLength(3)
    expect(rows()[2]).toHaveTextContent('پاس‌شده')
    await user.click(screen.getByRole('tab', { name: /دریافتی/ }))
    expect(rows()).toHaveLength(1)
    expect(rows()[0]).toHaveTextContent('از علی')
  })

  it('clears a cheque on its due date from its account', async () => {
    const { requests, user } = setup()
    renderWithProviders(<ChequesPage />)
    await screen.findByText('چک‌های صادره‌ی پاس‌نشده')
    await user.click(within(rows()[0]).getByRole('button', { name: 'پاس شد' }))
    const dialog = await screen.findByRole('dialog', { name: 'پاس شدن چک' })
    expect(within(dialog).getByRole('combobox', { name: 'از حساب' })).toHaveTextContent('کارت ملت')
    await user.click(within(dialog).getByRole('button', { name: /در تاریخ سررسید/ }))
    await user.click(within(dialog).getByRole('button', { name: 'ثبت' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toEqual({ method: 'POST', url: '/cheques/1/status', body: { status: 'CLEARED', accountId: 1, date: addDaysIso(today, -2) } })
  })

  it('marks a cheque as bounced', async () => {
    const { requests, user } = setup()
    renderWithProviders(<ChequesPage />)
    await screen.findByText('چک‌های صادره‌ی پاس‌نشده')
    await user.click(screen.getByRole('button', { name: 'گزینه‌های چک به فروشگاه' }))
    await user.click(await screen.findByRole('menuitem', { name: 'برگشت خورد' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toEqual({ method: 'POST', url: '/cheques/2/status', body: { status: 'BOUNCED' } })
  })

  it('records a received cheque with its Sayad number in Latin digits', async () => {
    const { requests, user } = setup([])
    renderWithProviders(<ChequesPage />)
    await user.click(await screen.findByRole('button', { name: 'ثبت اولین چک' }))
    const dialog = await screen.findByRole('dialog', { name: 'چک جدید' })
    await user.click(within(dialog).getByRole('radio', { name: 'چک دریافتی' }))
    await user.type(within(dialog).getByLabelText('مبلغ'), '5000000')
    await user.type(within(dialog).getByLabelText(/از طرف/), 'علی')
    await user.type(within(dialog).getByLabelText(/شناسه‌ی صیادی/), '۱۲۳۴')
    await user.click(within(dialog).getByRole('button', { name: 'ذخیره' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('شناسه‌ی صیادی ۱۶ رقم است.')
    await user.type(within(dialog).getByLabelText(/شناسه‌ی صیادی/), '۵۶۷۸۹۰۱۲۳۴۵۶')
    await user.click(within(dialog).getByRole('button', { name: 'ذخیره' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toMatchObject({
      method: 'POST', url: '/cheques',
      body: { direction: 'RECEIVED', amount: '5000000', counterparty: 'علی', sayadId: '1234567890123456', accountId: null, counterAccountId: null },
    })
  })
})
