import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AiDraft } from '@/lib/api/types'
import { authStatus, CATEGORIES, COMMODITIES, makeAccount } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { DraftList } from './DraftList'

function draft(overrides: Partial<AiDraft>): AiDraft {
  return {
    ref: 'ai:' + 'a'.repeat(32), type: 'EXPENSE', date: '2026-10-05', accountId: 1, amount: '180000', toAccountId: null,
    toAmount: null, categoryId: 2, description: 'ناهار', confidence: 'HIGH', warnings: [], duplicate: false, recorded: false,
    ...overrides,
  }
}

function setup() {
  const commits: unknown[] = []
  stubApi((url, init) => {
    if (url === '/auth/status') return { body: authStatus() }
    if (url.startsWith('/accounts')) return { body: [makeAccount({ id: 1, name: 'کارت ملت' })] }
    if (url === '/categories') return { body: CATEGORIES }
    if (url === '/commodities') return { body: COMMODITIES }
    if (url === '/ai/drafts/commit') {
      const body = JSON.parse(String(init.body)) as { drafts: unknown[] }
      commits.push(body)
      return { body: { created: body.drafts.length, skipped: 0, ids: [] } }
    }
  })
  return { commits, user: userEvent.setup() }
}

describe('DraftList', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows each draft with its warnings and records one only on request', async () => {
    const { commits, user } = setup()
    renderWithProviders(
      <DraftList drafts={[
        draft({ warnings: ['حساب مشخص نبود؛ «کارت ملت» انتخاب شد.'] }),
        draft({ ref: 'ai:' + 'b'.repeat(32), accountId: null, amount: '95000', description: 'اسنپ', categoryId: 4 }),
      ]} />,
    )
    const first = (await screen.findByText('ناهار')).closest('div.rounded-xl') as HTMLElement
    expect(within(first).getByText('حساب مشخص نبود؛ «کارت ملت» انتخاب شد.')).toBeInTheDocument()
    expect(await within(first).findByText(/خوراک › رستوران/)).toBeInTheDocument()

    // the second one has no account yet: it cannot be recorded, and "record all" needs two ready drafts
    const second = screen.getByText('اسنپ').closest('div.rounded-xl') as HTMLElement
    expect(within(second).getByText('حساب را انتخاب کنید.')).toBeInTheDocument()
    expect(within(second).getByRole('button', { name: 'ثبت' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: /ثبت ۲ تراکنش/ })).not.toBeInTheDocument()
    expect(commits).toHaveLength(0)

    await user.click(within(first).getByRole('button', { name: 'ثبت' }))
    expect(await within(first).findByText('ثبت شد')).toBeInTheDocument()
    expect(commits).toEqual([{ drafts: [{
      ref: 'ai:' + 'a'.repeat(32), type: 'EXPENSE', date: '2026-10-05', accountId: 1, amount: '180000', toAccountId: null,
      toAmount: null, categoryId: 2, description: 'ناهار',
    }] }])
    expect(within(first).queryByRole('button', { name: 'ثبت' })).not.toBeInTheDocument()
  })

  it('records every ready draft at once and drops the dismissed ones', async () => {
    const { commits, user } = setup()
    renderWithProviders(
      <DraftList drafts={[
        draft({}),
        draft({ ref: 'ai:' + 'b'.repeat(32), description: 'اسنپ', amount: '95000', categoryId: 4 }),
        draft({ ref: 'ai:' + 'c'.repeat(32), description: 'نان', amount: '25000', categoryId: 1 }),
      ]} />,
    )
    const bread = (await screen.findByText('نان')).closest('div.rounded-xl') as HTMLElement
    await user.click(within(bread).getByRole('button', { name: 'رد' }))
    expect(screen.queryByText('نان')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'ثبت ۲ تراکنش' }))
    await waitFor(() => expect(commits).toHaveLength(1))
    expect((commits[0] as { drafts: { ref: string }[] }).drafts.map((d) => d.ref)).toEqual(['ai:' + 'a'.repeat(32), 'ai:' + 'b'.repeat(32)])
    expect(await screen.findAllByText('ثبت شد')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: /تراکنش$/ })).not.toBeInTheDocument()
  })
})
