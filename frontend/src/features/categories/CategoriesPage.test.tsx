import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { authStatus, CATEGORIES } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { CategoriesPage } from './CategoriesPage'

function setup() {
  const requests: { method: string; url: string; body?: unknown }[] = []
  stubApi((url, init) => {
    const method = init.method ?? 'GET'
    if (url === '/auth/status') return { body: authStatus() }
    if (url === '/categories' && method === 'GET') return { body: CATEGORIES }
    if (url.startsWith('/categories')) {
      requests.push({ method, url, body: init.body ? JSON.parse(String(init.body)) : undefined })
      return method === 'DELETE' ? { status: 204 } : { status: 201, body: CATEGORIES[0] }
    }
    if (url.startsWith('/')) return { body: [] }
  })
  return { requests, user: userEvent.setup() }
}

describe('CategoriesPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('lists expense categories as a two-level tree and switches to income', async () => {
    const { user } = setup()
    renderWithProviders(<CategoriesPage />)
    expect(await screen.findByText('خوراک')).toBeInTheDocument()
    expect(screen.getByText('رستوران')).toBeInTheDocument()
    expect(screen.getByText('تاکسی اینترنتی')).toBeInTheDocument()
    expect(screen.queryByText('حقوق')).not.toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'درآمد' }))
    expect(await screen.findByText('حقوق')).toBeInTheDocument()
    expect(screen.queryByText('خوراک')).not.toBeInTheDocument()
  })

  it('creates a category with an icon', async () => {
    const { requests, user } = setup()
    renderWithProviders(<CategoriesPage />)
    await screen.findByText('خوراک')
    await user.click(screen.getByRole('button', { name: 'دسته‌ی جدید' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('نام'), 'ورزش')
    await user.click(within(dialog).getByRole('radio', { name: 'ورزش' }))
    await user.click(within(dialog).getByRole('button', { name: 'ذخیره' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toEqual({ method: 'POST', url: '/categories', body: { name: 'ورزش', kind: 'EXPENSE', parentId: null, icon: 'dumbbell' } })
  })

  it('deletes a category, leaving its transactions uncategorized by default', async () => {
    const { requests, user } = setup()
    renderWithProviders(<CategoriesPage />)
    await screen.findByText('خوراک')
    await user.click(screen.getByRole('button', { name: 'گزینه‌های حمل‌ونقل' }))
    await user.click(await screen.findByRole('menuitem', { name: 'حذف' }))
    const dialog = await screen.findByRole('dialog', { name: 'حذف «حمل‌ونقل»' })
    expect(dialog).toHaveTextContent('زیرمجموعه‌های این دسته (۱ مورد) هم حذف می‌شوند.')
    await user.click(within(dialog).getByRole('button', { name: 'حذف' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toMatchObject({ method: 'DELETE', url: '/categories/3' })
  })

  it('does not offer deletion of system categories', async () => {
    const { user } = setup()
    renderWithProviders(<CategoriesPage />)
    await screen.findByText('کارمزد بانکی')
    await user.click(screen.getByRole('button', { name: 'گزینه‌های کارمزد بانکی' }))
    expect(await screen.findByRole('menuitem', { name: 'حذف' })).toHaveAttribute('aria-disabled', 'true')
  })
})
