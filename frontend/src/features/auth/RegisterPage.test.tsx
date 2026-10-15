import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PASSWORD_TOO_LONG } from '@/lib/password'
import { makeMe } from '@/test/fixtures'
import { renderRoutes, stubApi } from '@/test/utils'
import { RegisterPage } from './RegisterPage'

describe('RegisterPage passwords', () => {
  afterEach(() => vi.unstubAllGlobals())

  it.each(['a'.repeat(73), 'م'.repeat(37), '🔐'.repeat(19)])('explains an oversized password before submitting', async (password) => {
    const registrations: unknown[] = []
    stubApi((url, init) => {
      if (url === '/auth/status') return { body: { authenticated: false, registrationOpen: true, hasUsers: true, user: null } }
      if (url === '/auth/register') { registrations.push(init.body); return { status: 201 } }
    })
    const user = userEvent.setup()
    renderRoutes([{ path: '/register', element: <RegisterPage /> }], '/register')
    fireEvent.change(await screen.findByLabelText('نام نمایشی'), { target: { value: 'سینا' } })
    fireEvent.change(screen.getByLabelText('نام کاربری'), { target: { value: 'sina' } })
    fireEvent.change(screen.getByLabelText('رمز عبور'), { target: { value: password } })
    fireEvent.change(screen.getByLabelText('تکرار رمز عبور'), { target: { value: password } })
    await user.click(screen.getByRole('button', { name: 'ثبت‌نام' }))
    expect(await screen.findByText(PASSWORD_TOO_LONG)).toBeInTheDocument()
    expect(registrations).toHaveLength(0)
  })

  it('submits a Persian password at the byte limit unchanged', async () => {
    const registrations: unknown[] = []
    const password = 'م'.repeat(36)
    stubApi((url, init) => {
      if (url === '/auth/status') return { body: { authenticated: false, registrationOpen: true, hasUsers: true, user: null } }
      if (url === '/auth/register') { registrations.push(JSON.parse(String(init.body))); return { status: 201, body: makeMe() } }
    })
    const user = userEvent.setup()
    renderRoutes([{ path: '/register', element: <RegisterPage /> }, { path: '/', element: <p>داشبورد</p> }], '/register')
    fireEvent.change(await screen.findByLabelText('نام نمایشی'), { target: { value: 'سینا' } })
    fireEvent.change(screen.getByLabelText('نام کاربری'), { target: { value: 'sina' } })
    fireEvent.change(screen.getByLabelText('رمز عبور'), { target: { value: password } })
    fireEvent.change(screen.getByLabelText('تکرار رمز عبور'), { target: { value: password } })
    await user.click(screen.getByRole('button', { name: 'ثبت‌نام' }))
    await waitFor(() => expect(registrations).toEqual([expect.objectContaining({ username: 'sina', password })]))
  })
})
