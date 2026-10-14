import { expect, test } from '@playwright/test'
import { api, register } from './support'
import type { Commodity } from '../src/lib/api/types'

test('an ordinary user adds an Iranian stock, values holdings and stops automatic quotes', async ({ page }) => {
  await register(page)
  await page.goto('/assets')
  await page.getByRole('button', { name: 'واحد جدید' }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByRole('radio', { name: 'بورس ایران' }).click()
  await dialog.getByLabel('نماد یا نام شرکت و صندوق').fill('فولاد')
  await dialog.getByRole('button', { name: 'جست‌وجو' }).click()
  await dialog.getByRole('radio', { name: /فولاد مبارکه اصفهان/ }).click()
  await dialog.getByRole('button', { name: 'افزودن نماد' }).click()
  await expect(dialog).not.toBeVisible()
  await expect(page.getByText(/نماد فولاد · قیمت خودکار/)).toBeVisible()

  const call = api(page)
  const stock = (await call<Commodity[]>('GET', '/commodities')).find((commodity) => commodity.stock?.symbol === 'فولاد')!
  expect(stock.stock?.instrumentId).toBe('46348559193224090')
  expect(stock.latestPrice?.priceToman).toBe('3535.8')
  const account = await call<{ balance: string; valueToman: string }>('POST', '/accounts', {
    name: 'سهام فولاد', type: 'INVESTMENT', commodity: stock.code, openingBalance: '100',
  })
  expect(account.balance).toBe('100')
  expect(account.valueToman).toBe('353580')

  await page.reload()
  await page.getByRole('button', { name: /فولاد.*شخصی/ }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: 'دریافت قیمت نماد' }).click()
  await expect(dialog.getByText('TSETMC', { exact: true })).toBeVisible()
  await dialog.getByRole('button', { name: 'توقف دریافت خودکار' }).click()
  await expect(dialog.getByRole('button', { name: 'اتصال به نماد بورس' })).toBeVisible()
  const stopped = (await call<Commodity[]>('GET', '/commodities')).find((commodity) => commodity.code === stock.code)!
  expect(stopped.stock).toBeNull()
  expect(stopped.latestPrice?.priceToman).toBe('3535.8')
})
