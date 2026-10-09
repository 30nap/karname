import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'
import { api, bankAccount, register, todayIso } from './support'

test('transactions download as a CSV for Excel and everything as a JSON backup', async ({ page }) => {
  await register(page)
  const account = await bankAccount(page, 'ملت حقوق', '50000000')
  await api(page)('POST', '/transactions', { type: 'EXPENSE', date: todayIso(), accountId: account.id, amount: '320000', description: 'خرید کتاب' })
  await page.goto('/data')

  const [csv] = await Promise.all([page.waitForEvent('download'), page.getByRole('link', { name: 'دانلود CSV' }).click()])
  const text = await readFile(await csv.path(), 'utf8')
  // a byte-order mark, so Excel opens the Persian text correctly
  expect(text.charCodeAt(0)).toBe(0xfeff)
  expect(text).toContain('خرید کتاب')

  const [backup] = await Promise.all([page.waitForEvent('download'), page.getByRole('link', { name: 'دانلود پشتیبان' }).click()])
  const data = JSON.parse(await readFile(await backup.path(), 'utf8'))
  expect(data.format).toBe('karname-backup')
  expect(data.tables.transactions.length).toBeGreaterThanOrEqual(2)
})
