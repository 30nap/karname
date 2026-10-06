import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AppNotification } from '@/lib/api/types'
import { authStatus } from '@/test/fixtures'
import { renderRoutes, stubApi } from '@/test/utils'
import { NotificationBell } from './NotificationBell'

const NOTIFICATIONS: AppNotification[] = [
  { id: 5, type: 'LOAN_OVERDUE', severity: 'CRITICAL', title: 'قسط معوق: وام مسکن', body: 'قسط ۱ به مبلغ ۱٬۰۰۰٬۰۰۰ تومان از ۱۷ شهریور ۱۴۰۵ پرداخت نشده است.',
    link: '/loans/3', createdAt: new Date(Date.now() - 5 * 60_000).toISOString(), read: false },
  { id: 6, type: 'BUDGET_WARNING', severity: 'WARNING', title: 'بودجه‌ی خوراک: ۸۵٪', body: null, link: '/budgets',
    createdAt: new Date(Date.now() - 3 * 3_600_000).toISOString(), read: false },
  { id: 4, type: 'GOAL_REACHED', severity: 'INFO', title: 'به هدف «لپ‌تاپ» رسیدید', body: null, link: '/goals', createdAt: '2026-09-01T06:00:00Z', read: true },
]

function setup() {
  const requests: string[] = []
  stubApi((url, init) => {
    if (url === '/auth/status') return { body: authStatus() }
    if (init.method === 'POST') {
      requests.push(url)
      return { status: 204 }
    }
    if (url === '/notifications/count') return { body: { unread: 2 } }
    if (url.startsWith('/notifications')) return { body: NOTIFICATIONS }
  })
  return { requests, user: userEvent.setup() }
}

function render() {
  return renderRoutes([
    { path: '/', element: <NotificationBell /> },
    { path: '/loans/:id', element: <p>صفحه‌ی وام</p> },
  ])
}

describe('NotificationBell', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows the unread count and opens the linked page, marking it read', async () => {
    const { requests, user } = setup()
    const { router } = render()
    await user.click(await screen.findByRole('button', { name: 'اعلان‌ها، ۲ خوانده‌نشده' }))
    const dialog = await screen.findByRole('dialog')
    const items = await within(dialog).findAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(items[0]).toHaveTextContent('فوری، خوانده‌نشده: قسط معوق: وام مسکن')
    expect(items[0]).toHaveTextContent('۵ دقیقه پیش')
    expect(items[1]).toHaveTextContent('۳ ساعت پیش')
    await user.click(within(items[0]).getByRole('button'))
    await waitFor(() => expect(requests).toEqual(['/notifications/5/read']))
    await waitFor(() => expect(router.state.location.pathname).toBe('/loans/3'))
  })

  it('marks everything read', async () => {
    const { requests, user } = setup()
    render()
    await user.click(await screen.findByRole('button', { name: /اعلان‌ها/ }))
    await user.click(await screen.findByRole('button', { name: 'خواندن همه' }))
    await waitFor(() => expect(requests).toEqual(['/notifications/read-all']))
  })
})
