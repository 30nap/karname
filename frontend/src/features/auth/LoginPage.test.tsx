import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderRoutes, stubApi } from '@/test/utils'
import { LoginPage } from './LoginPage'

const me = {
  id: 1, username: 'sina', displayName: 'سینا', role: 'ADMIN', totpEnabled: true,
  settings: { displayUnit: 'TOMAN', digitStyle: 'PERSIAN', theme: 'SYSTEM', wealthUnits: ['USD'], inflationRate: null, aiEnabled: true, aiShareDescriptions: true },
}

describe('LoginPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('asks for the TOTP code when the server requires it, then signs in', async () => {
    const logins: unknown[] = []
    stubApi((url, init) => {
      if (url === '/auth/status') return { body: { authenticated: false, registrationOpen: false, hasUsers: true, user: null } }
      if (url === '/auth/login') {
        const body = JSON.parse(String(init.body))
        logins.push(body)
        if (!body.totpCode) return { status: 401, body: { detail: 'کد ورود دومرحله‌ای را وارد کنید.', code: 'auth.totpRequired' } }
        return { body: me }
      }
    })
    const user = userEvent.setup()
    const { router } = renderRoutes([
      { path: '/login', element: <LoginPage /> },
      { path: '/', element: <p>داشبورد</p> },
    ], '/login')

    await user.type(await screen.findByLabelText('نام کاربری'), 'sina')
    await user.type(screen.getByLabelText('رمز عبور'), 'secret-pass')
    await user.click(screen.getByRole('button', { name: 'ورود' }))

    const code = await screen.findByLabelText('کد ورود دومرحله‌ای')
    await user.type(code, '123456')
    await user.click(screen.getByRole('button', { name: 'تأیید و ورود' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/'))
    expect(logins).toHaveLength(2)
    expect(logins[1]).toMatchObject({ username: 'sina', password: 'secret-pass', totpCode: '123456', rememberMe: true })
  })

  it('shows the server error for wrong credentials', async () => {
    stubApi((url) => {
      if (url === '/auth/status') return { body: { authenticated: false, registrationOpen: true, hasUsers: true, user: null } }
      if (url === '/auth/login') return { status: 401, body: { detail: 'نام کاربری یا رمز عبور اشتباه است.', code: 'auth.invalidCredentials' } }
    })
    const user = userEvent.setup()
    renderRoutes([{ path: '/login', element: <LoginPage /> }], '/login')
    await user.type(await screen.findByLabelText('نام کاربری'), 'sina')
    await user.type(screen.getByLabelText('رمز عبور'), 'wrong')
    await user.click(screen.getByRole('button', { name: 'ورود' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('نام کاربری یا رمز عبور اشتباه است.')
    expect(screen.getByRole('link', { name: 'ثبت‌نام کنید' })).toBeInTheDocument()
  })

  it('validates required fields in Persian', async () => {
    stubApi((url) => (url === '/auth/status' ? { body: { authenticated: false, registrationOpen: false, hasUsers: true, user: null } } : undefined))
    const user = userEvent.setup()
    renderRoutes([{ path: '/login', element: <LoginPage /> }], '/login')
    await user.click(await screen.findByRole('button', { name: 'ورود' }))
    expect(await screen.findByText('نام کاربری را وارد کنید.')).toBeInTheDocument()
    expect(screen.getByText('رمز عبور را وارد کنید.')).toBeInTheDocument()
  })
})
