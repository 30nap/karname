import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AiProposal } from '@/lib/api/types'
import { authStatus, COMMODITIES, makeMe } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { ProposalList } from './ProposalList'

const LOAN: AiProposal = {
  ref: 'a'.repeat(32), kind: 'LOAN', title: 'وام مسکن', status: 'PENDING', link: null, expiresAt: '2026-10-13T08:30:00Z',
  lines: [
    { label: 'مبلغ وام', value: '200000000', format: 'MONEY', unit: null, main: true },
    { label: 'نرخ سالانه', value: '23', format: 'PERCENT', unit: null, main: false },
    { label: 'تعداد اقساط', value: '36', format: 'NUMBER', unit: null, main: false },
    { label: 'سررسید قسط اول', value: '2026-11-05', format: 'DATE', unit: null, main: false },
    { label: 'واریز به', value: 'ملت حقوق', format: 'TEXT', unit: null, main: false },
  ],
  effects: ['حساب وام «وام مسکن» ساخته می‌شود.'],
  warnings: ['حساب پرداخت اقساط مشخص نبود؛ «ملت حقوق» در نظر گرفته شد.'],
}

const GOAL: AiProposal = {
  ...LOAN, ref: 'b'.repeat(32), kind: 'GOAL', title: 'هدف «صندوق مهاجرت»', effects: [], warnings: [],
  lines: [{ label: 'مبلغ هدف', value: '15000', format: 'QUANTITY', unit: 'USD', main: true }],
}

function setup(digitStyle: 'PERSIAN' | 'LATIN' = 'PERSIAN', displayUnit: 'TOMAN' | 'RIAL' = 'TOMAN') {
  const posted: string[] = []
  stubApi((url, init) => {
    if (url === '/auth/status') return { body: authStatus(makeMe({ digitStyle, displayUnit })) }
    if (url === '/commodities') return { body: COMMODITIES }
    if (url.startsWith('/ai/proposals/') && init.method === 'POST') {
      posted.push(url)
      const ref = url.split('/')[3]
      const card = [LOAN, GOAL].find((p) => p.ref === ref)!
      return { body: { ...card, status: url.endsWith('/apply') ? 'APPLIED' : 'DISMISSED', link: '/loans/7' } }
    }
  })
  return { posted, user: userEvent.setup() }
}

const plain = (t: string | null | undefined) => (t ?? '').replace(/[‎‏]/g, '')

describe('ProposalList', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows every value to be saved, with the main amount in words, and what else happens', async () => {
    setup()
    renderWithProviders(<ProposalList proposals={[LOAN]} />)
    const card = (await screen.findByText('وام مسکن')).closest('[data-proposal]') as HTMLElement
    expect(card).toHaveAttribute('data-proposal', 'LOAN')
    expect(within(card).getByText('وام')).toBeInTheDocument()
    await waitFor(() => expect(plain(card.textContent)).toContain('۲۰۰٬۰۰۰٬۰۰۰ تومان'))
    expect(within(card).getByText('دویست میلیون تومان')).toBeInTheDocument()
    expect(plain(card.textContent)).toContain('۲۳٪')
    expect(plain(card.textContent)).toContain('۳۶')
    expect(within(card).getByText('۱۴ آبان ۱۴۰۵')).toBeInTheDocument()
    expect(within(card).getByText('ملت حقوق')).toBeInTheDocument()
    expect(within(card).getByText('حساب وام «وام مسکن» ساخته می‌شود.')).toBeInTheDocument()
    expect(within(card).getByText(/در نظر گرفته شد/)).toBeInTheDocument()
  })

  it('spells the amount in the unit the user reads it in', async () => {
    setup('PERSIAN', 'RIAL')
    renderWithProviders(<ProposalList proposals={[LOAN]} />)
    expect(await screen.findByText('دو میلیارد ریال')).toBeInTheDocument()
  })

  it('records only on the user\'s click', async () => {
    const { posted, user } = setup()
    renderWithProviders(<ProposalList proposals={[LOAN]} />)
    await screen.findByText('وام مسکن')
    expect(posted).toEqual([])
    await user.click(screen.getByRole('button', { name: 'ثبت' }))
    await waitFor(() => expect(posted).toEqual([`/ai/proposals/${LOAN.ref}/apply`]))
  })

  it('can be dismissed, and several are recorded together one by one', async () => {
    const { posted, user } = setup()
    renderWithProviders(<ProposalList proposals={[LOAN, GOAL]} />)
    await user.click(await screen.findByRole('button', { name: 'ثبت هر ۲ مورد' }))
    await waitFor(() => expect(posted).toEqual([`/ai/proposals/${LOAN.ref}/apply`, `/ai/proposals/${GOAL.ref}/apply`]))
    await user.click(screen.getAllByRole('button', { name: 'رد' })[0])
    await waitFor(() => expect(posted).toContain(`/ai/proposals/${LOAN.ref}/dismiss`))
  })

  it('once decided, says so and offers no buttons', async () => {
    setup()
    renderWithProviders(<ProposalList proposals={[{ ...LOAN, status: 'APPLIED', link: '/loans/7' }, { ...GOAL, status: 'EXPIRED' }]} />)
    expect(await screen.findByText('ثبت شد')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /مشاهده/ })).toHaveAttribute('href', '/loans/7')
    expect(screen.getByText('منقضی شد')).toBeInTheDocument()
    expect(screen.getByText(/از دستیار دوباره بخواهید/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'ثبت' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'رد' })).not.toBeInTheDocument()
    // effects and warnings belong to the decision, not to what was decided
    expect(screen.queryByText(/در نظر گرفته شد/)).not.toBeInTheDocument()
  })
})
