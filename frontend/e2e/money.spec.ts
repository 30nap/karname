import { expect, test, type Locator } from '@playwright/test'
import { api, pick, register } from './support'

test('accounts, income, an expense and a dollar purchase add up', async ({ page }) => {
  await register(page)

  // the first account, through the form
  await page.getByRole('button', { name: 'ساخت اولین حساب' }).click()
  const form = page.getByRole('dialog')
  await pick(page, form.getByLabel('نوع حساب'), 'حساب بانکی')
  await form.getByLabel('نام').fill('ملت حقوق')
  await form.getByLabel(/موجودی فعلی/).fill('85000000')
  await form.getByRole('button', { name: 'ساخت حساب' }).click()
  await expect(form).toBeHidden()
  await api(page)('POST', '/accounts', { name: 'دلار نقد', type: 'CURRENCY', commodity: 'USD' })

  await page.goto('/transactions')
  const record = async (fill: (dialog: Locator) => Promise<void>) => {
    await page.getByRole('button', { name: 'تراکنش جدید' }).click()
    const dialog = page.getByRole('dialog', { name: 'تراکنش جدید' })
    await fill(dialog)
    await dialog.getByRole('button', { name: 'ثبت', exact: true }).click()
    await expect(dialog).toBeHidden()
  }
  await record(async (dialog) => {
    await dialog.getByRole('radio', { name: 'درآمد' }).click()
    await dialog.getByLabel('مبلغ').fill('45000000')
    await pick(page, dialog.getByLabel('دسته‌بندی'), 'حقوق و دستمزد')
    await dialog.getByLabel('شرح').fill('حقوق مهر')
  })
  await record(async (dialog) => {
    await dialog.getByLabel('مبلغ').fill('1850000')
    // amounts are echoed in words under the field
    await expect(dialog.getByText('یک میلیون و هشتصد و پنجاه هزار تومان')).toBeVisible()
    await pick(page, dialog.getByLabel('دسته‌بندی'), 'رستوران')
    await dialog.getByLabel('شرح').fill('رستوران با خانواده')
  })
  await record(async (dialog) => {
    await dialog.getByRole('radio', { name: 'انتقال' }).click()
    await pick(page, dialog.getByLabel('به حساب'), 'دلار نقد')
    await dialog.getByLabel('مقدار پرداختی').fill('10250000')
    await dialog.getByLabel('مقدار دریافتی').fill('100')
    await dialog.getByLabel('شرح').fill('خرید دلار')
  })
  await expect(page.getByText('رستوران با خانواده')).toBeVisible()

  // 85,000,000 + 45,000,000 − 1,850,000 − 10,250,000, shown with Persian digits
  await page.goto('/accounts')
  await expect(page.getByText('۱۱۷٬۹۰۰٬۰۰۰').first()).toBeVisible()
  // the purchase priced the dollar at 102,500 Toman: net worth is the bank balance plus $100 of it
  await page.goto('/')
  await expect(page.getByText('۱۲۸٬۱۵۰٬۰۰۰').first()).toBeVisible()
})
