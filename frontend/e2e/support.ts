import { expect, type Page } from '@playwright/test'

export const PASSWORD = 'secret-pass-123'
export const ADMIN = 'e2eadmin'
export const ADMIN_STATE = 'e2e/.auth/admin.json'
export const MOCK_PRICES = 'http://127.0.0.1:8099/prices.json'

export function uniqueName(prefix = 'e2e'): string {
  return prefix + Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
}

/** Registers a new user through the form; ends on the dashboard. */
export async function register(page: Page, username = uniqueName(), displayName = 'سینا'): Promise<string> {
  await page.goto('/register')
  await page.getByLabel('نام نمایشی').fill(displayName)
  await page.getByLabel('نام کاربری').fill(username)
  await page.getByLabel('رمز عبور', { exact: true }).fill(PASSWORD)
  await page.getByLabel('تکرار رمز عبور').fill(PASSWORD)
  // on a fresh instance the form sets up the administrator instead
  await page.getByRole('button', { name: /^(ثبت‌نام|ساخت حساب مدیر)$/ }).click()
  await page.waitForURL('/')
  return username
}

export async function login(page: Page, username: string, password = PASSWORD): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('نام کاربری').fill(username)
  await page.getByLabel('رمز عبور').fill(password)
  await page.getByRole('button', { name: 'ورود' }).click()
}

type Api = <T = unknown>(method: string, path: string, data?: unknown) => Promise<T>

/** The JSON API as the user signed in on {@code page}, CSRF header included. */
export function api(page: Page): Api {
  return async (method, path, data) => {
    const cookies = await page.context().cookies()
    const xsrf = cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? ''
    const response = await page.request.fetch('/api/v1' + path, { method, data, headers: { 'X-XSRF-TOKEN': xsrf } })
    if (!response.ok()) throw new Error(`${method} ${path} -> ${response.status()} ${await response.text()}`)
    return (response.status() === 204 ? null : await response.json()) as never
  }
}

export interface AccountRef {
  id: number
  name: string
}

/** A Toman bank account with an opening balance dated yesterday. */
export async function bankAccount(page: Page, name: string, balance: string): Promise<AccountRef> {
  const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10)
  return api(page)('POST', '/accounts', { name, type: 'BANK', commodity: 'IRT', bank: 'MELLAT', openingBalance: balance, openingDate: yesterday })
}

/** No element sticks out sideways (the page never scrolls horizontally). */
export async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow, `horizontal overflow on ${page.url()}`).toBeLessThanOrEqual(1)
}
