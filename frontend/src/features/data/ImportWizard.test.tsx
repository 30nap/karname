import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ImportPreview } from '@/lib/api/types'
import { authStatus, CATEGORIES, COMMODITIES, makeAccount } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { ImportWizard } from './ImportWizard'

const PREVIEW: ImportPreview = {
  headers: ['تاریخ', 'شرح', 'برداشت (ریال)', 'واریز (ریال)'],
  sample: [['تاریخ', 'شرح', 'برداشت (ریال)', 'واریز (ریال)'], ['1405/07/10', 'خرید هایپراستار', '1,250,000', '']],
  mapping: { date: 0, description: 1, amount: null, debit: 2, credit: 3, dateStyle: 'AUTO', unit: 'RIAL', hasHeader: true },
  rows: [
    { line: 2, date: '2026-10-02', amount: '-125000', description: 'خرید هایپراستار', categoryId: 2, duplicate: true, ref: 'import:' + 'a'.repeat(32), error: null },
    { line: 3, date: '2026-10-03', amount: '68000000', description: 'واریز حقوق', categoryId: 6, duplicate: false, ref: 'import:' + 'b'.repeat(32), error: null },
    { line: 4, date: '2026-10-04', amount: '-450', description: 'کارمزد', categoryId: null, duplicate: false, ref: 'import:' + 'c'.repeat(32), error: null },
    { line: 5, date: null, amount: null, description: 'بی‌تاریخ', categoryId: null, duplicate: false, ref: null, error: 'تاریخ «دیروز» خوانده نشد.' },
  ],
}

function setup() {
  const previews: Record<string, string>[] = []
  const commits: unknown[] = []
  stubApi((url, init) => {
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/commodities') return { body: COMMODITIES }
    if (url === '/categories') return { body: CATEGORIES }
    if (url.startsWith('/accounts')) return { body: [makeAccount({ id: 1 }), makeAccount({ id: 5, name: 'دلار', type: 'CURRENCY', commodity: 'USD' })] }
    if (url === '/io/import/preview') {
      previews.push(Object.fromEntries([...(init.body as FormData).entries()].filter(([k]) => k !== 'file').map(([k, v]) => [k, String(v)])))
      return { body: PREVIEW }
    }
    if (url === '/io/import/commit') {
      commits.push(JSON.parse(String(init.body)))
      return { body: { created: 2, skipped: 0 } }
    }
  })
  return { previews, commits, user: userEvent.setup() }
}

describe('ImportWizard', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('previews a statement, leaving duplicates and unreadable rows out, and records the rest', async () => {
    const { previews, commits, user } = setup()
    renderWithProviders(<ImportWizard />)
    await screen.findByText('کارت ملت')
    await user.upload(screen.getByLabelText('فایل صورت‌حساب'), new File(['csv'], 'mellat.csv', { type: 'text/csv' }))

    expect(await screen.findByText('واریز حقوق')).toBeInTheDocument()
    // a new file is read with detected columns
    expect(previews[0]).toEqual({ accountId: '1' })
    expect(screen.getByText('احتمالاً تکراری')).toBeInTheDocument()
    expect(screen.getByText('ردیف ۵: تاریخ «دیروز» خوانده نشد.')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'ردیف ۲' })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'ردیف ۳' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'ردیف ۵' })).toBeDisabled()
    // the suggested category shows under the description on phones and in its own column on wider screens
    expect(screen.getAllByText('حقوق')).toHaveLength(2)

    // changing how amounts are read previews again with the columns spelled out
    await user.click(screen.getByRole('radio', { name: 'تومان' }))
    await waitFor(() => expect(previews).toHaveLength(2))
    expect(previews[1]).toEqual({ accountId: '1', dateColumn: '0', descriptionColumn: '1', debitColumn: '2', creditColumn: '3',
      dateStyle: 'AUTO', unit: 'TOMAN', hasHeader: 'true' })

    await user.click(await screen.findByRole('button', { name: 'ثبت ۲ تراکنش' }))
    await waitFor(() => expect(commits).toHaveLength(1))
    expect(commits[0]).toEqual({
      accountId: 1,
      rows: [
        { date: '2026-10-03', amount: '68000000', description: 'واریز حقوق', categoryId: 6, ref: 'import:' + 'b'.repeat(32) },
        { date: '2026-10-04', amount: '-450', description: 'کارمزد', categoryId: null, ref: 'import:' + 'c'.repeat(32) },
      ],
    })
    expect(within(document.body).queryByText('واریز حقوق')).not.toBeInTheDocument()
  })
})
