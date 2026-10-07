import { test as setup } from '@playwright/test'
import { ADMIN, ADMIN_STATE, api, login, register } from './support'

// The first account of the instance is its administrator; it also routes every AI task to the
// offline model, so the assistant works without any key.
setup('administrator and offline AI', async ({ page }) => {
  const status = await (await page.request.get('/api/v1/auth/status')).json()
  if (status.hasUsers) {
    await login(page, ADMIN)
    await page.waitForURL('/')
  } else {
    await register(page, ADMIN, 'مدیر')
  }
  const call = api(page)
  const providers = await call<{ id: number; kind: string }[]>('GET', '/admin/ai/providers')
  const fake = providers.find((p) => p.kind === 'FAKE') ?? await call<{ id: number }>('POST', '/admin/ai/providers', { name: 'مدل آزمایشی', preset: 'FAKE' })
  await call('PUT', '/admin/ai/routes', ['CHAT', 'EXTRACT', 'REPORT'].map((task) => ({ task, providerId: fake.id, model: null, effort: null })))
  await page.context().storageState({ path: ADMIN_STATE })
})
