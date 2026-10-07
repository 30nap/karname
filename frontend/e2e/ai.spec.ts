import { expect, test } from '@playwright/test'
import { bankAccount, register } from './support'

// The administrator routed every AI task to the offline model in the setup step.

test('the assistant answers with the user\'s figures and drafts transactions to record', async ({ page }) => {
  await register(page)
  await bankAccount(page, 'ملت حقوق', '50000000')

  await page.goto('/assistant')
  await page.getByRole('button', { name: 'این ماه بیشتر از همه کجا خرج کردم؟', exact: true }).click()
  await expect(page.getByText('تفکیک بر اساس دسته')).toBeVisible()
  await expect(page).toHaveURL(/\/assistant\/\d+$/)

  await page.getByLabel('پیام').fill('دیروز ناهار ۱۸۰ و اسنپ ۹۵ تومن ثبت کن')
  await page.getByRole('button', { name: 'ارسال' }).click()
  await page.getByRole('button', { name: 'ثبت ۲ تراکنش' }).click()
  await expect(page.getByText('۲ تراکنش ثبت شد.')).toBeVisible()
  await expect(page.getByText('ثبت شد', { exact: true })).toHaveCount(2)

  await page.goto('/transactions')
  await expect(page.getByText('ناهار', { exact: true })).toBeVisible()
  await expect(page.getByText('اسنپ', { exact: true })).toBeVisible()
})

test('bank SMS become transactions only after confirmation', async ({ page }) => {
  await register(page)
  await bankAccount(page, 'ملت حقوق', '50000000')
  await page.goto('/sms')
  await page.getByLabel('متن پیامک‌ها').fill('بانک ملت\nبرداشت:1,250,000\n1405/07/10\n\nرمز پویا: 523981\n\nبانک ملت\nواریز 50,000,000 ریال\n1405/07/11')
  await page.getByRole('button', { name: 'بررسی پیامک‌ها' }).click()
  await expect(page.getByText('۳ پیامک بررسی شد | ۲ تراکنش پیدا شد | ۱ پیامک بدون تراکنش')).toBeVisible()
  await page.getByRole('button', { name: 'ثبت ۲ تراکنش' }).click()
  await expect(page.getByText('۲ تراکنش ثبت شد.')).toBeVisible()
})

test('the monthly report is written from the month\'s figures', async ({ page }) => {
  await register(page)
  const account = await bankAccount(page, 'ملت حقوق', '50000000')
  const today = new Date().toISOString().slice(0, 10)
  await page.request.fetch('/api/v1/transactions', {
    method: 'POST',
    headers: { 'X-XSRF-TOKEN': (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value ?? '' },
    data: { type: 'EXPENSE', date: today, accountId: account.id, amount: '2400000', description: 'خرید هفتگی' },
  })
  await page.goto('/reports?tab=ai')
  await page.getByRole('button', { name: 'نوشتن گزارش' }).click()
  await expect(page.getByRole('heading', { name: 'پیشنهادها' })).toBeVisible()
  await expect(page.getByText(/مروری بر/)).toBeVisible()
})
