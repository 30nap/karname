import { expect, test } from '@playwright/test'
import { api, bankAccount, categoryId, pick, register, todayIso } from './support'

test('a monthly budget follows the spending in its category', async ({ page }) => {
  await register(page)
  const account = await bankAccount(page, 'ملت حقوق', '50000000')
  await api(page)('POST', '/transactions', {
    type: 'EXPENSE', date: todayIso(), accountId: account.id, amount: '2500000', categoryId: await categoryId(page, 'سوپرمارکت'),
    description: 'خرید هفتگی',
  })

  await page.goto('/budgets')
  await page.getByRole('button', { name: 'بودجه‌ی جدید' }).first().click()
  const dialog = page.getByRole('dialog')
  await pick(page, dialog.getByLabel('دسته‌بندی'), /سوپرمارکت/)
  await dialog.getByLabel('سقف ماهانه').fill('10000000')
  await dialog.getByRole('button', { name: 'ذخیره' }).click()
  await expect(dialog).toBeHidden()

  // 2,500,000 of 10,000,000 spent
  await expect(page.getByText('سوپرمارکت').first()).toBeVisible()
  await expect(page.getByText(/۲٬۵۰۰٬۰۰۰/).first()).toBeVisible()
  await expect(page.getByText(/۱۰٬۰۰۰٬۰۰۰/).first()).toBeVisible()
})

test('a savings goal measures progress from its linked account', async ({ page }) => {
  await register(page)
  await bankAccount(page, 'پس‌انداز', '12000000')
  await page.goto('/goals')
  await page.getByRole('button', { name: 'تعریف اولین هدف' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('نام هدف').fill('سفر')
  await dialog.getByLabel('مبلغ هدف').fill('48000000')
  await dialog.getByRole('checkbox').first().check()
  await dialog.getByRole('button', { name: 'ذخیره' }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByText('سفر', { exact: true })).toBeVisible()
  // 12,000,000 of 48,000,000
  await expect(page.getByText(/۲۵٪/).first()).toBeVisible()
})
