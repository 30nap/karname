import { expect, test } from '@playwright/test'
import { bankAccount, expectNoHorizontalOverflow, register } from './support'

test('on a phone: bottom navigation, quick add button and no sideways scrolling', async ({ page }) => {
  await register(page)
  await bankAccount(page, 'ملت حقوق', '50000000')
  await page.goto('/')
  const nav = page.getByRole('navigation').last()
  for (const label of ['داشبورد', 'تراکنش‌ها', 'حساب‌ها', 'دستیار']) {
    await expect(nav.getByRole('link', { name: label })).toBeVisible()
  }
  await page.getByRole('button', { name: 'ثبت تراکنش' }).click()
  await expect(page.getByRole('dialog', { name: 'تراکنش جدید' })).toBeVisible()
  await page.keyboard.press('Escape')

  for (const path of ['/', '/transactions', '/accounts', '/assets', '/budgets', '/reports', '/goals', '/loans', '/cheques',
    '/assistant', '/sms', '/data', '/settings']) {
    await page.goto(path)
    await page.waitForLoadState('networkidle')
    await expectNoHorizontalOverflow(page)
  }
})

test('dark mode follows the chosen theme', async ({ page }) => {
  await register(page)
  await page.goto('/settings')
  await page.getByRole('radio', { name: 'تیره' }).click()
  await page.getByRole('button', { name: 'ذخیره‌ی تنظیمات' }).click()
  await expect(page.locator('html')).toHaveClass(/dark/)
  await page.reload()
  await expect(page.locator('html')).toHaveClass(/dark/)
})
