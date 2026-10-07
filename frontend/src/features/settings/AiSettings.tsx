import { AlertTriangle, CheckCircle2, FlaskConical, ListRestart, MoreVertical, Pencil, Plus, Sparkles, Trash2, X, XCircle } from 'lucide-react'
import { useMemo, useState } from 'react'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { FormField } from '@/components/ui/form-field'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { useFormat } from '@/app/preferences'
import {
  useAiModels, useAiPresets, useAiProviders, useAiRoutes, useAiSettings, useAiUsage, useDeleteAiProvider, useSaveAiProvider, useSaveAiRoutes,
  useSaveAiSettings, useTestAiProvider,
} from '@/features/ai/api'
import { ApiError } from '@/lib/api/client'
import type { AiEffort, AiPreset, AiPresetId, AiProvider, AiProviderInput, AiRoute, AiTask } from '@/lib/api/types'
import { cn } from '@/lib/cn'

const TASKS: { task: AiTask; label: string; hint: string }[] = [
  { task: 'CHAT', label: 'گفتگو با دستیار', hint: 'تحلیل با ابزارها؛ به مدل توانا نیاز دارد.' },
  { task: 'EXTRACT', label: 'خواندن متن و پیامک', hint: 'ثبت سریع، پیامک بانکی و دسته‌بندی؛ کوتاه و پرتکرار.' },
  { task: 'REPORT', label: 'گزارش ماهانه', hint: 'یک تحلیل دقیق در ماه.' },
]

const EFFORTS: { value: AiEffort; label: string }[] = [
  { value: 'LOW', label: 'کم' },
  { value: 'MEDIUM', label: 'متوسط' },
  { value: 'HIGH', label: 'زیاد' },
  { value: 'XHIGH', label: 'خیلی زیاد' },
  { value: 'MAX', label: 'حداکثر' },
]
const DEFAULT_EFFORT = '__default__'
const NO_PROVIDER = '__none__'

export function AiSettings() {
  return (
    <div className="grid grid-cols-1 gap-4">
      <ProvidersCard />
      <RoutesCard />
      <LimitsCard />
      <UsageCard />
    </div>
  )
}

// ---------------------------------------------------------------- providers

function ProvidersCard() {
  const { data: providers, isPending } = useAiProviders()
  const { data: presets } = useAiPresets()
  const remove = useDeleteAiProvider()
  const [editing, setEditing] = useState<AiProvider | 'new' | null>(null)
  const [deleting, setDeleting] = useState<AiProvider | null>(null)
  const labels = useMemo(() => new Map(presets?.presets.map((p) => [p.id, p.label])), [presets])
  return (
    <Card>
      <CardHeader className="flex-row flex-wrap items-start justify-between gap-3">
        <div className="space-y-1.5">
          <CardTitle>سرویس‌های هوش مصنوعی</CardTitle>
          <CardDescription>
            <bdi>Claude</bdi>، هر سرویس سازگار با <bdi>OpenAI</bdi> (مثل <bdi>Gemini</bdi>، <bdi>DeepSeek</bdi>، <bdi>OpenRouter</bdi> و
            درگاه‌های واسط) یا یک مدل روی سرور خودتان (<bdi>Ollama</bdi>). کلیدها رمزنگاری‌شده ذخیره می‌شوند و هرگز به مرورگر برنمی‌گردند.
          </CardDescription>
        </div>
        <Button onClick={() => setEditing('new')}><Plus />سرویس جدید</Button>
      </CardHeader>
      <CardContent>
        {isPending ? <Skeleton className="h-24" /> : !providers?.length ? (
          <p className="rounded-xl border border-dashed p-6 text-center text-sm text-muted-foreground">
            هنوز سرویسی تعریف نشده؛ تا وقتی تعریف نکنید، کارنامه بدون هوش مصنوعی کامل کار می‌کند.
          </p>
        ) : (
          <ul className="divide-y">
            {providers.map((p) => (
              <li key={p.id} className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-3 py-3">
                <div className="min-w-0">
                  <p className="flex flex-wrap items-center gap-2 font-medium">
                    {p.name}
                    <Badge variant="outline">{labels.get(p.preset) ?? p.preset}</Badge>
                    {!p.enabled ? <Badge variant="secondary">خاموش</Badge> : null}
                    {p.usedBy.length ? <Badge>{p.usedBy.map((t) => TASKS.find((x) => x.task === t)?.label).join('، ')}</Badge> : null}
                  </p>
                  <p className="truncate text-xs text-muted-foreground">
                    <bdi dir="ltr">{p.baseUrl}</bdi>
                    {p.defaultModel ? <> | <bdi dir="ltr">{p.defaultModel}</bdi></> : null}
                  </p>
                  <p className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground">
                    <span>{p.keyFromEnv ? 'کلید از متغیر محیطی' : p.hasApiKey ? 'کلید ذخیره‌شده' : 'بدون کلید'}</span>
                    <span>{p.supportsTools ? 'ابزار: دارد' : 'ابزار: ندارد (حالت خلاصه)'}</span>
                    {p.kind === 'OPENAI_COMPATIBLE' ? <span>{p.supportsJsonSchema ? 'JSON Schema: دارد' : 'JSON از راه دستور'}</span> : null}
                  </p>
                </div>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="icon-sm" aria-label={`گزینه‌های ${p.name}`}><MoreVertical /></Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent>
                    <DropdownMenuItem onSelect={() => setEditing(p)}><Pencil />ویرایش</DropdownMenuItem>
                    <DropdownMenuItem onSelect={() => setDeleting(p)} className="text-destructive focus:text-destructive"><Trash2 />حذف</DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
      <Dialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)}>
        {editing !== null && presets ? (
          <ProviderForm key={editing === 'new' ? 'new' : editing.id} provider={editing === 'new' ? undefined : editing} presets={presets.presets}
            envKeyAvailable={presets.envKeyAvailable} onClose={() => setEditing(null)} />
        ) : null}
      </Dialog>
      <ConfirmDialog open={deleting !== null} onOpenChange={(o) => !o && setDeleting(null)} destructive title={`حذف «${deleting?.name}»؟`}
        description="کارهایی که از این سرویس استفاده می‌کنند خاموش می‌شوند و گفتگوهای آن فقط خواندنی می‌مانند." confirmLabel="حذف"
        loading={remove.isPending}
        onConfirm={() => deleting && remove.mutate(deleting.id, { onSuccess: () => { setDeleting(null); toast.success('سرویس حذف شد.') } })} />
    </Card>
  )
}

function ProviderForm({ provider, presets, envKeyAvailable, onClose }: {
  provider?: AiProvider
  presets: AiPreset[]
  envKeyAvailable: boolean
  onClose: () => void
}) {
  const save = useSaveAiProvider()
  const models = useAiModels()
  const test = useTestAiProvider()
  const initial = presets.find((p) => p.id === (provider?.preset ?? 'ANTHROPIC')) ?? presets[0]
  const [presetId, setPresetId] = useState<AiPresetId>(initial.id)
  const preset = presets.find((p) => p.id === presetId) ?? initial
  const [name, setName] = useState(provider?.name ?? initial.label)
  const [baseUrl, setBaseUrl] = useState(provider?.baseUrl ?? initial.baseUrl)
  const [apiKey, setApiKey] = useState('')
  const [useEnvKey, setUseEnvKey] = useState(provider?.keyFromEnv ?? false)
  const [clearKey, setClearKey] = useState(false)
  const [headers, setHeaders] = useState<{ name: string; value: string; stored: boolean }[]>(
    provider?.headers.map((h) => ({ name: h.name, value: '', stored: h.hasValue })) ?? [])
  const [params, setParams] = useState<{ name: string; value: string }[]>(
    Object.entries(provider?.queryParams ?? {}).map(([n, v]) => ({ name: n, value: v })))
  const [model, setModel] = useState(provider?.defaultModel ?? initial.defaultModel ?? '')
  const [tools, setTools] = useState(provider?.supportsTools ?? initial.supportsTools)
  const [jsonSchema, setJsonSchema] = useState(provider?.supportsJsonSchema ?? initial.supportsJsonSchema)
  const [streamUsage, setStreamUsage] = useState(provider?.streamUsage ?? initial.streamUsage)
  const [fallback, setFallback] = useState(provider?.refusalFallback ?? true)
  const [enabled, setEnabled] = useState(provider?.enabled ?? true)
  const [error, setError] = useState<string | null>(null)
  const anthropic = preset.kind === 'ANTHROPIC'
  const fake = preset.kind === 'FAKE'
  const storedKey = !!provider?.hasApiKey && !provider.keyFromEnv

  const pickPreset = (id: AiPresetId) => {
    const next = presets.find((p) => p.id === id)
    if (!next) return
    setPresetId(id)
    if (!provider) {
      setName(next.label)
      setBaseUrl(next.baseUrl)
      setModel(next.defaultModel ?? '')
      setTools(next.supportsTools)
      setJsonSchema(next.supportsJsonSchema)
      setStreamUsage(next.streamUsage)
    }
    if (next.kind !== 'ANTHROPIC') setUseEnvKey(false)
  }

  const input = (): AiProviderInput => ({
    name: name.trim(), preset: presetId, baseUrl: baseUrl.trim() || undefined, apiKey: apiKey.trim() || undefined,
    clearApiKey: clearKey || undefined, useEnvKey: anthropic ? useEnvKey : false,
    headers: headers.filter((h) => h.name.trim()).map((h) => ({ name: h.name.trim(), value: h.value })),
    queryParams: Object.fromEntries(params.filter((p) => p.name.trim()).map((p) => [p.name.trim(), p.value.trim()])),
    defaultModel: model.trim() || undefined, supportsTools: tools, supportsJsonSchema: jsonSchema, streamUsage, refusalFallback: fallback, enabled,
  })
  const failed = (e: unknown) => setError(e instanceof ApiError ? e.message : 'انجام نشد.')

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    save.mutate({ id: provider?.id, input: input() }, {
      onSuccess: () => { toast.success(provider ? 'سرویس ذخیره شد.' : 'سرویس اضافه شد. حالا مدل هر کار را در بخش پایین تعیین کنید.'); onClose() },
      onError: failed,
    })
  }

  return (
    <DialogContent className="sm:max-w-2xl">
      <form onSubmit={submit} className="contents">
        <DialogHeader>
          <DialogTitle>{provider ? `ویرایش «${provider.name}»` : 'سرویس هوش مصنوعی جدید'}</DialogTitle>
          <DialogDescription>
            {anthropic ? 'Claude از طریق API رسمی Anthropic (یا درگاهی با همان API).'
              : fake ? 'مدل آزمایشی بدون اینترنت، فقط برای آزمایش و نمایش.'
                : 'هر سرویسی که API گفتگوی OpenAI (chat/completions) را پیاده کرده باشد.'}
          </DialogDescription>
        </DialogHeader>
        <DialogBody className="grid grid-cols-1 gap-4">
          {error ? <Alert variant="destructive">{error}</Alert> : null}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <FormField label="نوع سرویس">
              <Select value={presetId} onValueChange={(v) => v && pickPreset(v as AiPresetId)} disabled={!!provider}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  {presets.map((p) => <SelectItem key={p.id} value={p.id}>{p.label}</SelectItem>)}
                </SelectContent>
              </Select>
            </FormField>
            <FormField label="نام">
              <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={80} />
            </FormField>
          </div>
          {fake ? null : (
            <>
              <FormField label="نشانی پایه (Base URL)" hint={anthropic ? 'برای API رسمی همین نشانی را نگه دارید.' : 'تا پیش از /chat/completions؛ مثلاً …/v1'}>
                <Input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} dir="ltr" className="text-start" inputMode="url" />
              </FormField>
              {anthropic && envKeyAvailable ? (
                <label className="flex items-center justify-between gap-3 text-sm font-medium">
                  <span>
                    <span className="block">خواندن کلید از متغیر محیطی ANTHROPIC_API_KEY</span>
                    <span className="block text-xs font-normal text-muted-foreground">کلید روی سرور تنظیم شده و در پایگاه داده ذخیره نمی‌شود.</span>
                  </span>
                  <Switch checked={useEnvKey} onCheckedChange={setUseEnvKey} />
                </label>
              ) : null}
              {useEnvKey && anthropic ? null : (
                <FormField label="کلید API" optional={!preset.needsKey}
                  hint={storedKey ? (
                    <span>
                      {clearKey ? 'کلید ذخیره‌شده با ذخیره‌ی فرم پاک می‌شود.' : 'کلید ذخیره شده است؛ فقط برای تغییر، کلید تازه را وارد کنید.'}{' '}
                      <button type="button" className="font-medium text-primary underline-offset-4 hover:underline"
                        onClick={() => { setClearKey(!clearKey); setApiKey('') }}>
                        {clearKey ? 'نگه داشتن کلید' : 'پاک کردن کلید'}
                      </button>
                    </span>
                  ) : undefined}>
                  <Input value={apiKey} onChange={(e) => { setApiKey(e.target.value); setClearKey(false) }} type="password" dir="ltr"
                    className="text-start" autoComplete="off" placeholder={storedKey && !clearKey ? '•••••• (ذخیره‌شده)' : ''} />
                </FormField>
              )}
            </>
          )}
          <ModelField model={model} onModel={setModel}
            onFetch={() => { setError(null); models.mutate({ id: provider?.id, input: input() }, { onError: failed }) }}
            fetching={models.isPending} options={models.data} />
          {fake ? null : (
            <details className="rounded-xl border p-3 [&_summary]:cursor-pointer">
              <summary className="text-sm font-medium">تنظیمات پیشرفته</summary>
              <div className="mt-3 grid grid-cols-1 gap-4">
                <Pairs title="سرآیندهای درخواست" hint="مثلاً api-key برای Azure یا کلید درگاه واسط؛ مقدارها رمزنگاری‌شده ذخیره می‌شوند." secret
                  rows={headers.map((h) => ({ name: h.name, value: h.value, placeholder: h.stored ? '•••••• (ذخیره‌شده)' : '' }))}
                  onChange={(rows) => setHeaders(rows.map((r, i) => ({ name: r.name, value: r.value, stored: headers[i]?.stored ?? false })))} />
                <Pairs title="پارامترهای نشانی" hint="مثلاً api-version برای Azure OpenAI." rows={params.map((p) => ({ ...p, placeholder: '' }))}
                  onChange={(rows) => setParams(rows.map((r) => ({ name: r.name, value: r.value })))} />
              </div>
            </details>
          )}
          <div className="grid grid-cols-1 gap-3">
            {preset.kind === 'OPENAI_COMPATIBLE' ? (
              <>
                <Toggle label="فراخوانی ابزار (tool calling)" hint="خاموش: داده‌ها به‌صورت خلاصه همراه پرسش فرستاده می‌شود؛ برای مدل‌های کوچک محلی."
                  checked={tools} onChange={setTools} />
                <Toggle label="پشتیبانی از JSON Schema" hint="خاموش: قالب JSON در متن دستور خواسته و پاسخ با دقت خوانده می‌شود."
                  checked={jsonSchema} onChange={setJsonSchema} />
                <Toggle label="گزارش مصرف توکن در استریم" hint="اگر سرویس پارامتر stream_options را نمی‌پذیرد خاموش کنید."
                  checked={streamUsage} onChange={setStreamUsage} />
              </>
            ) : null}
            {anthropic ? (
              <Toggle label="مدل جایگزین هنگام رد درخواست" hint="اگر فیلترهای ایمنی مدل درخواستی را رد کنند، مدل توصیه‌شده‌ی Anthropic پاسخ می‌دهد."
                checked={fallback} onChange={setFallback} />
            ) : null}
            <Toggle label="فعال" checked={enabled} onChange={setEnabled} />
          </div>
          <div className="grid gap-2">
            <Button type="button" variant="outline" className="w-fit" loading={test.isPending}
              onClick={() => { setError(null); test.mutate({ id: provider?.id, input: input(), model: model.trim() || undefined }, { onError: failed }) }}>
              <FlaskConical />آزمایش اتصال
            </Button>
            {test.data ? <TestResult result={test.data} /> : null}
          </div>
        </DialogBody>
        <DialogFooter>
          <Button type="button" variant="outline" onClick={onClose}>انصراف</Button>
          <Button type="submit" loading={save.isPending}>ذخیره</Button>
        </DialogFooter>
      </form>
    </DialogContent>
  )
}

function ModelField({ model, onModel, onFetch, fetching, options }: {
  model: string
  onModel: (model: string) => void
  onFetch: () => void
  fetching: boolean
  options: string[] | undefined
}) {
  return (
    <div className="grid gap-2">
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-end">
        <FormField label="مدل پیش‌فرض">
          <Input value={model} onChange={(e) => onModel(e.target.value)} dir="ltr" className="text-start" maxLength={200} />
        </FormField>
        <Button type="button" variant="outline" onClick={onFetch} loading={fetching}><ListRestart />دریافت فهرست مدل‌ها</Button>
      </div>
      {options ? (
        options.length ? (
          <Select value={options.includes(model) ? model : ''} onValueChange={(v) => v && onModel(v)}>
            <SelectTrigger aria-label="انتخاب از فهرست مدل‌ها"><SelectValue placeholder={`انتخاب از ${options.length} مدل`} /></SelectTrigger>
            <SelectContent>{options.map((m) => <SelectItem key={m} value={m}><bdi dir="ltr">{m}</bdi></SelectItem>)}</SelectContent>
          </Select>
        ) : <p className="text-xs text-muted-foreground">سرویس مدلی گزارش نکرد.</p>
      ) : <p className="text-xs text-muted-foreground">شناسه‌ی دقیق مدل؛ می‌توانید آن را از فهرست مدل‌های سرویس انتخاب کنید.</p>}
    </div>
  )
}

function Pairs({ title, hint, rows, onChange, secret }: {
  title: string
  hint: string
  rows: { name: string; value: string; placeholder: string }[]
  onChange: (rows: { name: string; value: string }[]) => void
  secret?: boolean
}) {
  const set = (i: number, change: Partial<{ name: string; value: string }>) => onChange(rows.map((r, j) => (j === i ? { ...r, ...change } : r)))
  return (
    <fieldset className="grid gap-2">
      <legend className="mb-1 text-sm font-medium">{title} <span className="text-xs font-normal text-muted-foreground">(اختیاری)</span></legend>
      {rows.map((r, i) => (
        <div key={i} className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)_auto] gap-2">
          <Input aria-label="نام" value={r.name} dir="ltr" className="text-start" onChange={(e) => set(i, { name: e.target.value })} />
          <Input aria-label={`مقدار ${r.name}`} value={r.value} dir="ltr" className="text-start" type={secret ? 'password' : 'text'} autoComplete="off"
            placeholder={r.placeholder} onChange={(e) => set(i, { value: e.target.value })} />
          <Button type="button" variant="ghost" size="icon" aria-label="حذف" onClick={() => onChange(rows.filter((_, j) => j !== i))}><X /></Button>
        </div>
      ))}
      <Button type="button" variant="outline" size="sm" className="w-fit" onClick={() => onChange([...rows, { name: '', value: '' }])}><Plus />افزودن</Button>
      <p className="text-xs text-muted-foreground">{hint}</p>
    </fieldset>
  )
}

function Toggle({ label, hint, checked, onChange }: { label: string; hint?: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="flex items-center justify-between gap-3">
      <span>
        <span className="block text-sm font-medium">{label}</span>
        {hint ? <span className="block text-xs text-muted-foreground">{hint}</span> : null}
      </span>
      <Switch checked={checked} onCheckedChange={onChange} />
    </label>
  )
}

function TestResult({ result }: { result: { ok: boolean; latencyMs: number; model: string | null; toolCalling: boolean | null; reply: string | null; error: string | null } }) {
  const f = useFormat()
  if (!result.ok) return <Alert variant="destructive"><XCircle /><span className="break-words">{result.error}</span></Alert>
  return (
    <Alert className={cn(result.toolCalling === false && 'border-warning/30 bg-warning/10 [&>svg]:text-warning')}>
      {result.toolCalling === false ? <AlertTriangle /> : <CheckCircle2 className="text-income" />}
      <div className="grid gap-1">
        <span>اتصال برقرار است | {f.number(result.latencyMs)} میلی‌ثانیه{result.model ? <> | <bdi dir="ltr">{result.model}</bdi></> : null}</span>
        {result.toolCalling === true ? <span className="text-xs text-muted-foreground">مدل ابزار را درست فراخوانی کرد.</span> : null}
        {result.toolCalling === false ? (
          <span className="text-xs">مدل ابزار را فراخوانی نکرد؛ اگر این مدل از ابزار پشتیبانی نمی‌کند، «فراخوانی ابزار» را خاموش کنید.</span>
        ) : null}
        {result.reply ? <span className="text-xs text-muted-foreground">پاسخ: {result.reply}</span> : null}
      </div>
    </Alert>
  )
}

// ---------------------------------------------------------------- routing

function RoutesCard() {
  const { data: routes, isPending } = useAiRoutes()
  const { data: providers = [] } = useAiProviders()
  const save = useSaveAiRoutes()
  const [draft, setDraft] = useState<Record<AiTask, AiRoute> | null>(null)
  const current = draft ?? (routes ? Object.fromEntries(routes.map((r) => [r.task, r])) as Record<AiTask, AiRoute> : null)
  const set = (task: AiTask, change: Partial<AiRoute>) => current && setDraft({ ...current, [task]: { ...current[task], ...change } })
  const submit = () => current && save.mutate(TASKS.map(({ task }) => ({
    task, providerId: current[task].providerId, model: current[task].model?.trim() || null, effort: current[task].effort,
  })), {
    onSuccess: () => { setDraft(null); toast.success('مدل کارها ذخیره شد.') },
    onError: (e) => toast.error(e instanceof ApiError ? e.message : 'ذخیره نشد.'),
  })
  return (
    <Card>
      <CardHeader>
        <CardTitle>مدل هر کار</CardTitle>
        <CardDescription>
          هر کار می‌تواند سرویس و مدل جدا داشته باشد؛ مثلاً پیامک‌ها با مدل محلی (برای حریم خصوصی) و تحلیل‌ها با Claude. «عمق فکر» فقط برای
          Claude است: بیشتر یعنی دقیق‌تر ولی کندتر و پرهزینه‌تر.
        </CardDescription>
      </CardHeader>
      <CardContent className="grid grid-cols-1 gap-4">
        {isPending || !current ? <Skeleton className="h-40" /> : TASKS.map(({ task, label, hint }) => {
          const route = current[task]
          const provider = providers.find((p) => p.id === route.providerId)
          return (
            <div key={task} role="group" aria-label={label}
              className="grid grid-cols-1 gap-3 rounded-xl border p-3 md:grid-cols-[12rem_minmax(0,1fr)_minmax(0,1fr)_11rem] md:items-end">
              <div>
                <p className="text-sm font-medium">{label}</p>
                <p className="text-xs text-muted-foreground">{hint}</p>
              </div>
              <FormField label="سرویس">
                <Select value={route.providerId === null ? NO_PROVIDER : String(route.providerId)}
                  onValueChange={(v) => v && set(task, { providerId: v === NO_PROVIDER ? null : Number(v),
                    model: v === NO_PROVIDER ? null : providers.find((p) => p.id === Number(v))?.defaultModel ?? null })}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value={NO_PROVIDER}>خاموش</SelectItem>
                    {providers.map((p) => <SelectItem key={p.id} value={String(p.id)}>{p.name}</SelectItem>)}
                  </SelectContent>
                </Select>
              </FormField>
              <FormField label="مدل">
                <Input value={route.model ?? ''} dir="ltr" className="text-start" disabled={route.providerId === null}
                  placeholder={provider?.defaultModel ?? ''} onChange={(e) => set(task, { model: e.target.value })} />
              </FormField>
              <FormField label="عمق فکر">
                <Select value={route.effort ?? DEFAULT_EFFORT} disabled={provider?.kind !== 'ANTHROPIC'}
                  onValueChange={(v) => v && set(task, { effort: v === DEFAULT_EFFORT ? null : (v as AiEffort) })}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value={DEFAULT_EFFORT}>پیش‌فرض ({EFFORTS.find((e) => e.value === route.defaultEffort)?.label})</SelectItem>
                    {EFFORTS.map((e) => <SelectItem key={e.value} value={e.value}>{e.label}</SelectItem>)}
                  </SelectContent>
                </Select>
              </FormField>
            </div>
          )
        })}
        <div className="flex justify-end">
          <Button onClick={submit} loading={save.isPending} disabled={!draft}><Sparkles />ذخیره‌ی مدل کارها</Button>
        </div>
      </CardContent>
    </Card>
  )
}

// ---------------------------------------------------------------- limits and usage

function LimitsCard() {
  const f = useFormat()
  const { data } = useAiSettings()
  const save = useSaveAiSettings()
  const [limit, setLimit] = useState<string | null>(null)
  const value = limit ?? (data ? String(data.dailyLimit) : '')
  return (
    <Card>
      <CardHeader>
        <CardTitle>سقف استفاده</CardTitle>
        <CardDescription>بیشترین تعداد درخواست هوش مصنوعی هر کاربر در روز (هر پیام گفتگو، ثبت سریع، پیامک یا گزارش یک درخواست است).</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap items-end gap-3">
        <FormField label="درخواست در روز برای هر کاربر" className="w-48">
          <Input value={value} onChange={(e) => setLimit(e.target.value.replace(/\D/g, ''))} inputMode="numeric" dir="ltr" className="text-end" />
        </FormField>
        <Button variant="outline" loading={save.isPending} disabled={!limit || Number(limit) < 1}
          onClick={() => save.mutate(Number(limit), {
            onSuccess: (r) => { setLimit(null); toast.success(`سقف روزانه ${f.number(r.dailyLimit)} درخواست شد.`) },
            onError: (e) => toast.error(e instanceof ApiError ? e.message : 'ذخیره نشد.'),
          })}>
          ذخیره
        </Button>
      </CardContent>
    </Card>
  )
}

const TASK_LABELS: Record<string, string> = {
  CHAT: 'گفتگو', QUICK_ADD: 'ثبت سریع', SMS: 'پیامک', CATEGORIZE: 'دسته‌بندی', REPORT: 'گزارش ماهانه', TEST: 'آزمایش اتصال',
}

function UsageCard() {
  const f = useFormat()
  const { data, isPending } = useAiUsage(30)
  const totals = (data ?? []).reduce((t, d) => ({
    operations: t.operations + d.operations,
    tokens: t.tokens + d.inputTokens + d.outputTokens + d.cacheReadTokens + d.cacheWriteTokens,
    cost: t.cost + (d.costUsd ? Number(d.costUsd) : 0),
    unknownCost: t.unknownCost || d.costUsd === null,
  }), { operations: 0, tokens: 0, cost: 0, unknownCost: false })
  return (
    <Card>
      <CardHeader>
        <CardTitle>مصرف ۳۰ روز اخیر</CardTitle>
        <CardDescription>
          {f.number(totals.operations)} درخواست | {f.compact(totals.tokens)} توکن
          {totals.cost > 0 ? ` | حدود ${f.number(totals.cost.toFixed(2))} دلار` : ''}
          {totals.unknownCost ? ' (هزینه‌ی سرویس‌های غیر از Claude محاسبه نمی‌شود)' : ''}
        </CardDescription>
      </CardHeader>
      <CardContent>
        {isPending ? <Skeleton className="h-24" /> : !data?.length ? (
          <p className="text-sm text-muted-foreground">هنوز درخواستی ثبت نشده است.</p>
        ) : (
          <div className="overflow-x-auto rounded-xl border">
            <table className="w-full min-w-[32rem] text-sm">
              <thead className="bg-muted/60 text-xs text-muted-foreground">
                <tr>
                  <th className="p-2 text-start font-medium">روز</th>
                  <th className="p-2 text-start font-medium">کار</th>
                  <th className="p-2 text-end font-medium">درخواست</th>
                  <th className="p-2 text-end font-medium">توکن ورودی / خروجی</th>
                  <th className="p-2 text-end font-medium">هزینه (دلار)</th>
                </tr>
              </thead>
              <tbody>
                {data.map((d) => (
                  <tr key={`${d.day}-${d.task}`} className="border-t">
                    <td className="p-2 whitespace-nowrap">{f.dateShort(d.day)}</td>
                    <td className="p-2">{TASK_LABELS[d.task] ?? d.task}{d.failures ? <Badge variant="warning" className="ms-2">{f.number(d.failures)} ناموفق</Badge> : null}</td>
                    <td className="p-2 text-end tabular">{f.number(d.operations)}</td>
                    <td className="p-2 text-end tabular whitespace-nowrap">{f.compact(d.inputTokens + d.cacheReadTokens + d.cacheWriteTokens)} / {f.compact(d.outputTokens)}</td>
                    <td className="p-2 text-end tabular">{d.costUsd === null ? '—' : f.number(Number(d.costUsd).toFixed(3))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
