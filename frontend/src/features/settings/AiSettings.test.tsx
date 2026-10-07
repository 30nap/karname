import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AiPreset, AiProvider, AiRoute } from '@/lib/api/types'
import { authStatus } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { AiSettings } from './AiSettings'

const PRESETS: AiPreset[] = [
  { id: 'ANTHROPIC', kind: 'ANTHROPIC', label: 'Anthropic (Claude)', baseUrl: 'https://api.anthropic.com', defaultModel: 'claude-opus-5-5',
    supportsTools: true, supportsJsonSchema: true, streamUsage: true, needsKey: true },
  { id: 'DEEPSEEK', kind: 'OPENAI_COMPATIBLE', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', defaultModel: 'deepseek-chat',
    supportsTools: true, supportsJsonSchema: false, streamUsage: true, needsKey: true },
]

const CLAUDE: AiProvider = {
  id: 3, name: 'Claude', kind: 'ANTHROPIC', preset: 'ANTHROPIC', baseUrl: 'https://api.anthropic.com', hasApiKey: true, keyFromEnv: false,
  headers: [], queryParams: {}, defaultModel: 'claude-opus-5-5', supportsTools: true, supportsJsonSchema: true, streamUsage: true,
  refusalFallback: true, enabled: true, usedBy: ['CHAT'],
}

const ROUTES: AiRoute[] = [
  { task: 'CHAT', providerId: 3, providerName: 'Claude', model: 'claude-opus-5-5', effort: null, defaultEffort: 'MEDIUM' },
  { task: 'EXTRACT', providerId: null, providerName: null, model: null, effort: null, defaultEffort: 'LOW' },
  { task: 'REPORT', providerId: null, providerName: null, model: null, effort: null, defaultEffort: 'HIGH' },
]

function setup(providers: AiProvider[] = []) {
  const requests: { method: string; url: string; body?: unknown }[] = []
  stubApi((url, init) => {
    const method = init.method ?? 'GET'
    if (url === '/auth/status') return { body: authStatus() }
    if (method !== 'GET') {
      const body = init.body ? JSON.parse(String(init.body)) : undefined
      requests.push({ method, url, body })
      if (url.startsWith('/admin/ai/providers/models')) return { body: ['deepseek-chat', 'deepseek-reasoner'] }
      if (url.startsWith('/admin/ai/providers/test')) {
        return { body: { ok: true, latencyMs: 420, model: 'deepseek-chat', toolCalling: true, reply: null, error: null } }
      }
      if (url === '/admin/ai/routes') return { body: ROUTES }
      return { body: { ...CLAUDE, ...(body as object) } }
    }
    if (url === '/admin/ai/presets') return { body: { presets: PRESETS, envKeyAvailable: true } }
    if (url === '/admin/ai/providers') return { body: providers }
    if (url === '/admin/ai/routes') return { body: ROUTES }
    if (url === '/admin/ai/settings') return { body: { dailyLimit: 100 } }
    if (url.startsWith('/admin/ai/usage')) return { body: [] }
  })
  return { requests, user: userEvent.setup() }
}

describe('AiSettings', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('adds a provider from a preset, lists its models and tests it before saving', async () => {
    const { requests, user } = setup()
    renderWithProviders(<AiSettings />)
    await user.click(await screen.findByRole('button', { name: 'سرویس جدید' }))
    const dialog = await screen.findByRole('dialog', { name: 'سرویس هوش مصنوعی جدید' })
    expect(within(dialog).getByText('خواندن کلید از متغیر محیطی ANTHROPIC_API_KEY')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('combobox', { name: 'نوع سرویس' }))
    await user.click(await screen.findByRole('option', { name: 'DeepSeek' }))
    expect(within(dialog).getByLabelText('نام')).toHaveValue('DeepSeek')
    expect(within(dialog).getByLabelText('نشانی پایه (Base URL)')).toHaveValue('https://api.deepseek.com/v1')
    expect(within(dialog).getByLabelText('مدل پیش‌فرض')).toHaveValue('deepseek-chat')
    expect(within(dialog).queryByText('خواندن کلید از متغیر محیطی ANTHROPIC_API_KEY')).not.toBeInTheDocument()
    expect(within(dialog).getByRole('switch', { name: /پشتیبانی از JSON Schema/ })).not.toBeChecked()

    await user.type(within(dialog).getByLabelText('کلید API'), 'sk-test-123')
    await user.click(within(dialog).getByRole('button', { name: 'دریافت فهرست مدل‌ها' }))
    expect(await within(dialog).findByRole('combobox', { name: 'انتخاب از فهرست مدل‌ها' })).toHaveTextContent('deepseek-chat')
    await user.click(within(dialog).getByRole('button', { name: 'آزمایش اتصال' }))
    expect(await within(dialog).findByText('مدل ابزار را درست فراخوانی کرد.')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'ذخیره' }))
    await waitFor(() => expect(requests).toHaveLength(3))
    expect(requests[0]).toMatchObject({ method: 'POST', url: '/admin/ai/providers/models', body: { preset: 'DEEPSEEK', apiKey: 'sk-test-123' } })
    expect(requests[1]).toMatchObject({ method: 'POST', url: '/admin/ai/providers/test', body: { provider: { apiKey: 'sk-test-123' }, model: 'deepseek-chat' } })
    expect(requests[2]).toMatchObject({
      method: 'POST', url: '/admin/ai/providers',
      body: { name: 'DeepSeek', preset: 'DEEPSEEK', baseUrl: 'https://api.deepseek.com/v1', apiKey: 'sk-test-123', useEnvKey: false,
        defaultModel: 'deepseek-chat', supportsTools: true, supportsJsonSchema: false, enabled: true },
    })
  })

  it('never sends a stored key back when editing', async () => {
    const { requests, user } = setup([CLAUDE])
    renderWithProviders(<AiSettings />)
    await user.click(await screen.findByRole('button', { name: 'گزینه‌های Claude' }))
    await user.click(await screen.findByRole('menuitem', { name: 'ویرایش' }))
    const dialog = await screen.findByRole('dialog', { name: 'ویرایش «Claude»' })
    expect(within(dialog).getByLabelText('کلید API')).toHaveAttribute('placeholder', '•••••• (ذخیره‌شده)')
    await user.click(within(dialog).getByRole('switch', { name: /مدل جایگزین هنگام رد درخواست/ }))
    await user.click(within(dialog).getByRole('button', { name: 'ذخیره' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toMatchObject({ method: 'PUT', url: '/admin/ai/providers/3', body: { refusalFallback: false } })
    expect(requests[0].body).not.toHaveProperty('apiKey')
    expect(requests[0].body).not.toHaveProperty('clearApiKey')
  })

  it('routes a task to a provider with its default model', async () => {
    const { requests, user } = setup([CLAUDE])
    renderWithProviders(<AiSettings />)
    const reports = await screen.findByRole('group', { name: 'گزارش ماهانه' })
    expect(within(reports).getByLabelText('مدل')).toBeDisabled()
    await user.click(within(reports).getByRole('combobox', { name: 'سرویس' }))
    await user.click(await screen.findByRole('option', { name: 'Claude' }))
    expect(within(reports).getByLabelText('مدل')).toHaveValue('claude-opus-5-5')
    await user.click(screen.getByRole('button', { name: 'ذخیره‌ی مدل کارها' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toMatchObject({
      method: 'PUT', url: '/admin/ai/routes',
      body: [
        { task: 'CHAT', providerId: 3, model: 'claude-opus-5-5', effort: null },
        { task: 'EXTRACT', providerId: null, model: null, effort: null },
        { task: 'REPORT', providerId: 3, model: 'claude-opus-5-5', effort: null },
      ],
    })
  })
})
