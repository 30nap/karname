import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { authStatus } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { JalaliDatePicker } from './JalaliDatePicker'

describe('JalaliDatePicker', () => {
  beforeEach(() => stubApi((url) => (url === '/auth/status' ? { body: authStatus() } : undefined)))
  afterEach(() => vi.unstubAllGlobals())

  it('shows the Jalali date and selects a day from the month grid', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<JalaliDatePicker value="2026-10-06" onChange={onChange} />)
    await user.click(await screen.findByRole('button', { name: /۱۴ مهر ۱۴۰۵/ }))
    const grid = screen.getByRole('group', { name: 'مهر ۱۴۰۵' })
    expect(within(grid).getByRole('button', { name: '۱۴ مهر ۱۴۰۵' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(grid).getAllByRole('button')).toHaveLength(30)
    await user.click(within(grid).getByRole('button', { name: '۲۰ مهر ۱۴۰۵' }))
    expect(onChange).toHaveBeenCalledWith('2026-10-12')
  })

  it('moves between months, including across the year boundary', async () => {
    const user = userEvent.setup()
    renderWithProviders(<JalaliDatePicker value="2027-03-15" onChange={() => {}} />)
    await user.click(await screen.findByRole('button', { name: /۲۴ اسفند ۱۴۰۵/ }))
    await user.click(screen.getByRole('button', { name: 'ماه بعد' }))
    expect(screen.getByRole('group', { name: 'فروردین ۱۴۰۶' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'ماه قبل' }))
    await user.click(screen.getByRole('button', { name: 'ماه قبل' }))
    expect(screen.getByRole('group', { name: 'بهمن ۱۴۰۵' })).toBeInTheDocument()
  })

  it('supports keyboard navigation in right-to-left order', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<JalaliDatePicker value="2026-10-06" onChange={onChange} />)
    await user.click(await screen.findByRole('button', { name: /۱۴ مهر ۱۴۰۵/ }))
    within(screen.getByRole('group', { name: 'مهر ۱۴۰۵' })).getByRole('button', { name: '۱۴ مهر ۱۴۰۵' }).focus()
    await user.keyboard('{ArrowLeft}')
    expect(document.activeElement).toHaveAccessibleName('۱۵ مهر ۱۴۰۵')
    await user.keyboard('{ArrowDown}')
    expect(document.activeElement).toHaveAccessibleName('۲۲ مهر ۱۴۰۵')
    await user.keyboard('{Enter}')
    expect(onChange).toHaveBeenCalledWith('2026-10-14')
  })
})
