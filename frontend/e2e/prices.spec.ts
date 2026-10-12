import { expect, test } from '@playwright/test'
import { ADMIN_STATE, MOCK_PRICES, api, uniqueName } from './support'

test.use({ storageState: ADMIN_STATE })

test('a manual price is recorded for a unit', async ({ page }) => {
  await page.goto('/assets')
  await page.getByRole('button', { name: /یورو/ }).first().click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('قیمت هر یورو').fill('111000')
  await dialog.getByRole('button', { name: 'ثبت قیمت' }).click()
  await expect(dialog.getByText('۱۱۱٬۰۰۰').first()).toBeVisible()
})

test('an automatic source reads prices in Rial from an outside API', async ({ page }) => {
  await page.goto('/settings?tab=admin')
  const name = uniqueName('منبع')
  // configured through the API; the form itself is covered by component tests
  await api(page)('POST', '/admin/price-sources', {
    name, kind: 'JSON', url: MOCK_PRICES, unit: 'RIAL', headers: [], intervalMinutes: 60, enabled: false,
    mappings: [{ commodity: 'USD', path: '/data/usd/price', multiplier: null }],
  })
  await page.reload()
  await page.getByRole('button', { name: `دریافت حالا از ${name}` }).click()
  await expect(page.getByText(/^۱ قیمت دریافت/)).toBeVisible()

  // 1,025,000 Rial = 102,500 Toman, as this source's quote (which source's quote is in use depends on the others)
  await page.reload()
  await expect(page.getByRole('listitem').filter({ hasText: `${name}: ` }).last()).toContainText('۱۰۲٬۵۰۰')
})

test('a failing source shows the reason it gave, and another source supplies the price', async ({ page }) => {
  await page.goto('/settings?tab=admin')
  const call = api(page)
  const failing = uniqueName('خراب')
  const backup = uniqueName('پشتیبان')
  const source = (name: string, path: string, pointer: string) => ({
    name, kind: 'JSON', url: MOCK_PRICES.replace('/prices.json', path), unit: 'TOMAN', headers: [], intervalMinutes: 30, enabled: true,
    mappings: [{ commodity: 'USD', path: pointer, multiplier: null }],
  })
  const created = [
    await call<{ id: number }>('POST', '/admin/price-sources', source(failing, '/rejects.json', '/usd')),
    await call<{ id: number }>('POST', '/admin/price-sources', source(backup, '/prices-toman.json', '/usd')),
  ]
  try {
    await page.goto('/assets')
    await page.getByRole('button', { name: 'به‌روزرسانی قیمت‌ها' }).click()
    await expect(page.getByText(/پاسخ نداد: سرویس با کد HTTP ۴۰۰ پاسخ داد: Invalid srcCurrency/)).toBeVisible()

    await page.goto('/settings?tab=admin')
    await expect(page.getByText('سرویس با کد HTTP ۴۰۰ پاسخ داد: Invalid srcCurrency').first()).toBeVisible()
    const coverage = page.getByRole('listitem').filter({ hasText: `${backup}: ` }).first()
    await expect(coverage).toContainText('در استفاده')
    await expect(coverage).toContainText('۱۰۳٬۰۰۰')
  } finally {
    for (const { id } of created) await call('DELETE', `/admin/price-sources/${id}`)
  }
})
