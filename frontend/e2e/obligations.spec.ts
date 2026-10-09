import { expect, test } from '@playwright/test'
import { api, bankAccount, register } from './support'

test('a loan arrives in the account and its first installment is paid from it', async ({ page }) => {
  await register(page)
  await bankAccount(page, 'ملت حقوق', '50000000')
  await page.goto('/loans')
  await page.getByRole('button', { name: 'ثبت اولین وام' }).click()
  const dialog = page.getByRole('dialog', { name: 'وام جدید' })
  await dialog.getByLabel('نام').fill('وام خودرو')
  await dialog.getByLabel('مبلغ وام').fill('100000000')
  await expect(dialog.getByText('قسط ماهانه')).toBeVisible()
  await dialog.getByRole('button', { name: 'ذخیره' }).click()
  await page.waitForURL(/\/loans\/\d+$/)
  await expect(page.getByRole('heading', { name: 'وام خودرو' })).toBeVisible()
  const loanId = Number(page.url().split('/').pop())

  await page.getByRole('button', { name: 'پرداخت', exact: true }).first().click()
  const pay = page.getByRole('dialog', { name: /پرداخت قسط ۱/ })
  await pay.getByRole('button', { name: 'ثبت پرداخت' }).click()
  await expect(pay).toBeHidden()

  const loan = await api(page)<{ paidCount: number; installments: { amount: string }[] }>('GET', `/loans/${loanId}`)
  expect(loan.paidCount).toBe(1)
  // the account got the loan and paid one installment
  const accounts = await api(page)<{ name: string; balance: string }[]>('GET', '/accounts')
  const bank = accounts.find((a) => a.name === 'ملت حقوق')!
  expect(Number(bank.balance)).toBe(150_000_000 - Number(loan.installments[0].amount))
})

test('an issued cheque is recorded, then cleared from its account', async ({ page }) => {
  await register(page)
  await bankAccount(page, 'ملت حقوق', '50000000')
  await page.goto('/cheques')
  await page.getByRole('button', { name: 'ثبت اولین چک' }).click()
  const dialog = page.getByRole('dialog', { name: 'چک جدید' })
  await dialog.getByLabel('مبلغ').fill('7500000')
  await dialog.getByLabel(/در وجه/).fill('فروشگاه مبل')
  await dialog.getByLabel(/شناسه‌ی صیادی/).fill('1234567890123456')
  await dialog.getByRole('button', { name: 'ذخیره' }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByText(/فروشگاه مبل/).first()).toBeVisible()

  await page.getByRole('button', { name: 'پاس شد' }).first().click()
  const clear = page.getByRole('dialog', { name: 'پاس شدن چک' })
  await clear.getByRole('button', { name: 'ثبت' }).click()
  await expect(clear).toBeHidden()
  const accounts = await api(page)<{ name: string; balance: string }[]>('GET', '/accounts')
  expect(accounts.find((a) => a.name === 'ملت حقوق')!.balance).toBe('42500000')
})
