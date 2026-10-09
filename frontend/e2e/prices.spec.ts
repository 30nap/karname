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
  await expect(page.getByText('۱ قیمت دریافت شد.')).toBeVisible()

  // 1,025,000 Rial = 102,500 Toman
  await page.goto('/assets')
  await expect(page.getByRole('button', { name: /دلار آمریکا/ }).first()).toContainText('۱۰۲٬۵۰۰')
})
