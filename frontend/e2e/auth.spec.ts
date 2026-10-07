import { expect, test } from '@playwright/test'
import { login, register } from './support'

test('a new user registers, signs out and signs back in', async ({ page }) => {
  const username = await register(page)
  await expect(page.locator('html')).toHaveAttribute('dir', 'rtl')
  await expect(page.locator('html')).toHaveAttribute('lang', 'fa')
  await expect(page.getByRole('button', { name: 'ساخت اولین حساب' })).toBeVisible()

  // the account menu is labelled with the user's own name
  await page.getByRole('button', { name: new RegExp(username) }).click()
  await page.getByRole('menuitem', { name: 'خروج' }).click()
  await page.waitForURL('**/login')

  await login(page, username, 'not-the-password')
  await expect(page.getByText('نام کاربری یا رمز عبور اشتباه است.')).toBeVisible()
  await login(page, username)
  await page.waitForURL('/')
  await expect(page.getByRole('button', { name: 'ساخت اولین حساب' })).toBeVisible()
})

test('signed-out visitors are sent to the login page', async ({ page }) => {
  await page.goto('/transactions')
  await page.waitForURL('**/login**')
  await expect(page.getByRole('button', { name: 'ورود' })).toBeVisible()
})
