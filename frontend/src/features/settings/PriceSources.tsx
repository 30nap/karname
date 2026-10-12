import {
  AlertTriangle, ArrowDown, ArrowUp, Bitcoin, Braces, CheckCircle2, Coins, ExternalLink, FlaskConical, Globe, KeyRound, MoreVertical, Pencil,
  Plus, RefreshCw, Trash2, X,
} from 'lucide-react'
import { useRef, useState } from 'react'
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
import { Segmented } from '@/components/ui/segmented'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { Amount } from '@/components/finance/Amount'
import { useFormat } from '@/app/preferences'
import { useCommodities, useCommodityMap } from '@/features/commodities/api'
import { ApiError } from '@/lib/api/client'
import type {
  PriceCoverage, PriceMapping, PriceRunResult, PriceSource, PriceSourceInput, PriceSourceKind, PriceSourcePreset, PriceUnit, QuoteStatus,
} from '@/lib/api/types'
import { cn } from '@/lib/cn'
import { formatTimeAgo } from '@/lib/format/duration'
import {
  useDeletePriceSource, usePriceCoverage, usePricePresets, usePriceSources, useReorderPriceSources, useRunPriceSources, useSavePriceSource,
  useTestPriceSource,
} from './api'

const KIND_LABELS: Record<PriceSourceKind, string> = { NOBITEX: 'نوبیتکس', JSON: 'API دلخواه' }
const KEY = '{key}'
/** Beyond this difference from the current price, a test result is worth a second look (as on the server). */
const SUSPICIOUS = 0.2

const usesKey = (url: string, headers: { value: string }[]) => url.includes(KEY) || headers.some((h) => h.value.includes(KEY))

/** The stored form of a source, ready to send back with one field changed (secrets stay on the server). */
function toInput(source: PriceSource): PriceSourceInput {
  return {
    name: source.name, kind: source.kind, url: source.url, unit: source.unit, mappings: source.mappings, intervalMinutes: source.intervalMinutes,
    enabled: source.enabled, headers: source.headers.map((h) => ({ name: h.name, value: null })), apiKey: null,
  }
}

function TestResults({ result }: { result: PriceRunResult }) {
  const commodities = useCommodityMap()
  const f = useFormat()
  if (result.error) return <Alert variant="destructive"><AlertTriangle />{result.error}</Alert>
  return (
    <div className="overflow-x-auto rounded-xl border">
      <table className="w-full min-w-[26rem] text-sm">
        <thead className="bg-muted/60 text-xs text-muted-foreground">
          <tr>
            <th className="p-2 text-start font-medium">واحد</th>
            <th className="p-2 text-end font-medium">عدد خوانده‌شده</th>
            <th className="p-2 text-end font-medium">قیمت</th>
            <th className="p-2 text-end font-medium">قیمت فعلی</th>
          </tr>
        </thead>
        <tbody>
          {result.results.map((r) => {
            const commodity = commodities.get(r.commodity)
            const current = commodity.latestPrice?.priceToman ?? null
            const off = r.priceToman && current ? Math.abs(Number(r.priceToman) / Number(current) - 1) > SUSPICIOUS : false
            return (
              <tr key={r.commodity} className="border-t">
                <td className="p-2">{commodity.nameFa}</td>
                {r.error ? (
                  <td colSpan={3} className="p-2 text-end text-xs text-destructive">{r.error}</td>
                ) : (
                  <>
                    <td className="p-2 text-end tabular text-muted-foreground">{f.number(r.raw ?? '0', { maxFraction: 8 })}</td>
                    <td className={cn('p-2 text-end', off && 'text-warning')}>
                      <Amount value={r.priceToman} />
                      {off ? <span className="block text-xs">بیش از ۲۰٪ با قیمت فعلی فرق دارد؛ واحد (ریال یا تومان) و ضریب را بررسی کنید.</span> : null}
                    </td>
                    <td className="p-2 text-end text-muted-foreground">{current ? <Amount value={current} /> : '—'}</td>
                  </>
                )}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

const COVERAGE_ICONS = { CRYPTO: Bitcoin, GOLD_CURRENCY: Coins, ALL: Globe } as const

/** First step of a new source: a known service, or a configuration by hand. */
function PresetPicker({ onPick }: { onPick: (preset: PriceSourcePreset | null) => void }) {
  const { data: presets, isPending } = usePricePresets(true)
  return (
    <>
      <DialogHeader>
        <DialogTitle>منبع قیمت جدید</DialogTitle>
        <DialogDescription>یک سرویس آماده را انتخاب کنید یا هر API که قیمت را به‌صورت JSON می‌دهد دستی تنظیم کنید. همه‌ی تنظیمات بعداً قابل تغییرند.</DialogDescription>
      </DialogHeader>
      <DialogBody>
        {isPending ? <Skeleton className="h-40" /> : (
          <ul className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {presets?.map((p) => {
              const Icon = COVERAGE_ICONS[p.coverage]
              return (
                <li key={p.id}>
                  <button type="button" onClick={() => onPick(p)}
                    className="flex h-full w-full items-start gap-3 rounded-xl border p-3 text-start transition-colors hover:bg-muted/60 focus-visible:outline-2 focus-visible:outline-ring">
                    <Icon className="mt-0.5 size-5 shrink-0 text-primary" aria-hidden />
                    <span className="grid gap-1">
                      <span className="flex flex-wrap items-center gap-2 font-medium">
                        {p.name}
                        {p.keyHelp ? <Badge variant="outline"><KeyRound />کلید لازم</Badge> : <Badge variant="secondary">بدون کلید</Badge>}
                      </span>
                      <span className="text-xs text-muted-foreground">{p.description}</span>
                      {p.note ? <span className="text-xs text-warning">{p.note}</span> : null}
                    </span>
                  </button>
                </li>
              )
            })}
            <li>
              <button type="button" onClick={() => onPick(null)}
                className="flex h-full w-full items-start gap-3 rounded-xl border border-dashed p-3 text-start transition-colors hover:bg-muted/60 focus-visible:outline-2 focus-visible:outline-ring">
                <Braces className="mt-0.5 size-5 shrink-0 text-primary" aria-hidden />
                <span className="grid gap-1">
                  <span className="font-medium">API دلخواه</span>
                  <span className="text-xs text-muted-foreground">نشانی، سرآیندها و مسیر هر قیمت در پاسخ JSON را خودتان تعیین کنید.</span>
                </span>
              </button>
            </li>
          </ul>
        )}
      </DialogBody>
    </>
  )
}

type HeaderRow = { name: string; value: string; stored: boolean }

function SourceForm({ source, preset, onClose }: { source?: PriceSource; preset?: PriceSourcePreset | null; onClose: () => void }) {
  const { data: commodities = [] } = useCommodities()
  const save = useSavePriceSource()
  const test = useTestPriceSource()
  const [name, setName] = useState(source?.name ?? preset?.name ?? '')
  const [kind, setKind] = useState<PriceSourceKind>(source?.kind ?? preset?.kind ?? 'JSON')
  const [url, setUrl] = useState(source?.url ?? preset?.url ?? '')
  const [unit, setUnit] = useState<PriceUnit>(source?.unit ?? preset?.unit ?? 'TOMAN')
  const [headers, setHeaders] = useState<HeaderRow[]>(
    source?.headers.map((h) => ({ name: h.name, value: '', stored: h.hasValue }))
    ?? Object.entries(preset?.headers ?? {}).map(([n, v]) => ({ name: n, value: v, stored: false })))
  const [apiKey, setApiKey] = useState('')
  const [mappings, setMappings] = useState<PriceMapping[]>(source?.mappings ?? preset?.mappings ?? [{ commodity: 'GOLD18', path: '', multiplier: null }])
  const [interval, setIntervalMinutes] = useState(String(source?.intervalMinutes ?? preset?.intervalMinutes ?? 30))
  const [enabled, setEnabled] = useState(source?.enabled ?? true)
  const [error, setError] = useState<string | null>(null)
  const results = useRef<HTMLDivElement>(null)
  const priced = commodities.filter((c) => !c.custom && c.code !== 'IRT')
  const nobitex = kind === 'NOBITEX'
  // stored header values are hidden; a stored header kept as is may still carry {key}, as the source reported
  const needsKey = usesKey(url, headers) || (source?.usesKey === true && headers.some((h) => h.stored && !h.value))
  const presetId = source?.preset ?? preset?.id ?? null

  const input = (): PriceSourceInput => ({
    name: name.trim(), kind, url: url.trim() || null, unit: nobitex ? 'RIAL' : unit, intervalMinutes: Number(interval) || 30, enabled,
    headers: headers.filter((h) => h.name.trim()).map((h) => ({ name: h.name.trim(), value: h.value || null })),
    mappings: mappings.map((m) => ({ commodity: m.commodity, path: m.path.trim(), multiplier: m.multiplier?.trim() || null, unit: m.unit ?? null })),
    apiKey: needsKey ? apiKey.trim() || null : null,
    preset: presetId,
  })
  const setMapping = (i: number, change: Partial<PriceMapping>) => setMappings(mappings.map((m, j) => (j === i ? { ...m, ...change } : m)))
  const failed = (err: unknown) => setError(err instanceof ApiError ? err.message : 'انجام نشد.')

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    save.mutate({ id: source?.id, ...input() }, {
      onSuccess: () => { toast.success(source ? 'منبع قیمت ذخیره شد.' : 'منبع قیمت اضافه شد.'); onClose() },
      onError: failed,
    })
  }

  return (
    <form onSubmit={submit} className="contents">
      <DialogHeader>
        <DialogTitle>{source ? `ویرایش «${source.name}»` : preset ? `منبع جدید: ${preset.name}` : 'منبع قیمت جدید'}</DialogTitle>
        <DialogDescription>
          {preset?.description ?? (nobitex
            ? 'قیمت رمزارزها از بازارهای ریالی نوبیتکس، بدون نیاز به کلید. تتر را می‌توانید به دلار هم نسبت دهید تا نرخ دلار آزاد خودکار شود.'
            : 'هر سرویسی که قیمت را به‌صورت JSON می‌دهد: برای هر واحد، مسیر عدد در پاسخ را با اشاره‌گر JSON بنویسید؛ مثلاً /data/gold18/price.')}
        </DialogDescription>
      </DialogHeader>
      <DialogBody className="grid grid-cols-1 gap-4">
        {error ? <Alert variant="destructive">{error}</Alert> : null}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <FormField label="نام">
            <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={40} placeholder="مثلاً قیمت طلا" />
          </FormField>
          <FormField label="نوع">
            <Segmented<PriceSourceKind> ariaLabel="نوع منبع" value={kind} onChange={setKind}
              options={(['JSON', 'NOBITEX'] as const).map((k) => ({ value: k, label: KIND_LABELS[k] }))} />
          </FormField>
        </div>
        <FormField label="نشانی" optional={nobitex}
          hint={nobitex ? 'پیش‌فرض: https://apiv2.nobitex.ir' : 'اگر سرویس کلید را در نشانی می‌خواهد، به جای آن {key} بنویسید؛ مثلاً ?api_key={key}'}>
          <Input value={url} onChange={(e) => setUrl(e.target.value)} dir="ltr" className="text-start" inputMode="url"
            placeholder={nobitex ? 'https://apiv2.nobitex.ir' : 'https://example.com/prices.json'} />
        </FormField>
        {needsKey ? (
          <FormField label="کلید API"
            hint={<>
              {preset?.keyHelp ?? 'به جای {key} در نشانی و سرآیندها فرستاده می‌شود و هیچ‌وقت دوباره نمایش داده نمی‌شود.'}
              {preset?.website ? <> <a href={preset.website} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-primary hover:underline">سایت سرویس<ExternalLink className="size-3" /></a></> : null}
            </>}>
            <Input value={apiKey} onChange={(e) => setApiKey(e.target.value)} type="password" dir="ltr" className="text-start" autoComplete="off"
              placeholder={source?.hasApiKey ? '•••••• (ذخیره‌شده)' : ''} />
          </FormField>
        ) : null}
        {nobitex ? null : (
          <>
            <FormField label="واحد اعداد در پاسخ">
              <Segmented<PriceUnit> ariaLabel="واحد اعداد" value={unit} onChange={setUnit}
                options={[{ value: 'TOMAN', label: 'تومان' }, { value: 'RIAL', label: 'ریال' }]} />
            </FormField>
            <fieldset className="grid gap-2">
              <legend className="mb-1 text-sm font-medium">سرآیندهای درخواست <span className="text-xs font-normal text-muted-foreground">(اختیاری؛ مثلاً کلید API)</span></legend>
              {headers.map((h, i) => (
                <div key={i} className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)_auto] gap-2">
                  <Input aria-label="نام سرآیند" value={h.name} dir="ltr" className="text-start" placeholder="X-Api-Key"
                    onChange={(e) => setHeaders(headers.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
                  <Input aria-label={`مقدار ${h.name || 'سرآیند'}`} value={h.value} type="password" dir="ltr" className="text-start" autoComplete="off"
                    placeholder={h.stored ? '•••••• (ذخیره‌شده)' : ''}
                    onChange={(e) => setHeaders(headers.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))} />
                  <Button type="button" variant="ghost" size="icon" aria-label="حذف سرآیند" onClick={() => setHeaders(headers.filter((_, j) => j !== i))}><X /></Button>
                </div>
              ))}
              <Button type="button" variant="outline" size="sm" className="w-fit" onClick={() => setHeaders([...headers, { name: '', value: '', stored: false }])}>
                <Plus />سرآیند
              </Button>
            </fieldset>
          </>
        )}
        <fieldset className="grid gap-2">
          <legend className="mb-1 text-sm font-medium">قیمت‌ها</legend>
          {mappings.map((m, i) => (
            <div key={i} className="grid grid-cols-2 gap-2 rounded-xl border p-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)_5rem_6rem_auto] sm:border-0 sm:p-0">
              <Select value={m.commodity} onValueChange={(v) => v && setMapping(i, { commodity: v })}>
                <SelectTrigger aria-label="واحد"><SelectValue /></SelectTrigger>
                <SelectContent>
                  {priced.map((c) => <SelectItem key={c.code} value={c.code}>{c.nameFa}</SelectItem>)}
                </SelectContent>
              </Select>
              <Input aria-label={nobitex ? 'نماد در نوبیتکس' : 'مسیر در پاسخ'} value={m.path} dir="ltr" className="text-start"
                placeholder={nobitex ? 'usdt' : '/data/price'} onChange={(e) => setMapping(i, { path: e.target.value })} />
              <Input aria-label="ضریب" value={m.multiplier ?? ''} dir="ltr" className="text-start" inputMode="decimal" placeholder="ضریب"
                onChange={(e) => setMapping(i, { multiplier: e.target.value || null })} />
              {nobitex ? <span className="hidden sm:block" /> : (
                <Select value={m.unit ?? 'SOURCE'} onValueChange={(v) => setMapping(i, { unit: v === 'SOURCE' ? null : (v as PriceUnit) })}>
                  <SelectTrigger aria-label="واحد این عدد"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="SOURCE">مثل منبع</SelectItem>
                    <SelectItem value="TOMAN">تومان</SelectItem>
                    <SelectItem value="RIAL">ریال</SelectItem>
                  </SelectContent>
                </Select>
              )}
              <Button type="button" variant="ghost" size="icon" aria-label="حذف قیمت" disabled={mappings.length === 1} className="justify-self-end"
                onClick={() => setMappings(mappings.filter((_, j) => j !== i))}><X /></Button>
            </div>
          ))}
          <Button type="button" variant="outline" size="sm" className="w-fit"
            onClick={() => setMappings([...mappings, { commodity: priced.find((c) => !mappings.some((m) => m.commodity === c.code))?.code ?? 'USD', path: '', multiplier: null }])}>
            <Plus />قیمت
          </Button>
          <p className="text-xs text-muted-foreground">
            ضریب اختیاری است؛ مثلاً اگر سرویس قیمت هر مثقال را می‌دهد و شما قیمت هر گرم را می‌خواهید.
            {nobitex ? null : ' اگر پاسخ فهرستی از اقلام است، قلم را با فیلدش انتخاب کنید: /gold/[symbol=IR_GOLD_18K]/price'}
          </p>
        </fieldset>
        <div className="grid grid-cols-1 items-end gap-4 sm:grid-cols-2">
          <FormField label="به‌روزرسانی هر (دقیقه)" hint="بین ۵ دقیقه تا ۲۴ ساعت؛ برای سرویس‌های با سهمیه‌ی محدود، فاصله را بیشتر کنید.">
            <Input value={interval} onChange={(e) => setIntervalMinutes(e.target.value.replace(/\D/g, ''))} inputMode="numeric" dir="ltr" className="text-end" />
          </FormField>
          <label className="flex h-10 items-center justify-between gap-3 text-sm font-medium">
            دریافت خودکار فعال باشد
            <Switch checked={enabled} onCheckedChange={setEnabled} />
          </label>
        </div>
        <div className="grid gap-2">
          <Button type="button" variant="outline" className="w-fit" loading={test.isPending}
            onClick={() => {
              setError(null)
              test.mutate({ id: source?.id, ...input() }, {
                onSuccess: () => requestAnimationFrame(() => results.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })),
                onError: failed,
              })
            }}>
            <FlaskConical />آزمایش دریافت
          </Button>
          <div ref={results}>{test.data ? <TestResults result={test.data} /> : null}</div>
        </div>
      </DialogBody>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose}>انصراف</Button>
        <Button type="submit" loading={save.isPending}>ذخیره</Button>
      </DialogFooter>
    </form>
  )
}

/** A new source starts at the preset picker; an existing one opens its form. */
function SourceDialog({ source, onClose }: { source?: PriceSource; onClose: () => void }) {
  const [preset, setPreset] = useState<PriceSourcePreset | null | undefined>(source ? null : undefined)
  return (
    <DialogContent className="sm:max-w-2xl">
      {preset === undefined
        ? <PresetPicker onPick={setPreset} />
        : <SourceForm source={source} preset={preset} onClose={onClose} />}
    </DialogContent>
  )
}

function SourceRow({ source, rank, first, last, onMove, moving, onEdit, onDelete }: {
  source: PriceSource; rank: number; first: boolean; last: boolean; onMove: (step: -1 | 1) => void; moving: boolean
  onEdit: () => void; onDelete: () => void
}) {
  const f = useFormat()
  const commodities = useCommodityMap()
  const save = useSavePriceSource()
  const run = useRunPriceSources()
  return (
    <li className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-start gap-x-3 gap-y-2 py-3">
      <div className="flex flex-col items-center gap-0.5">
        <Button variant="ghost" size="icon-sm" aria-label={`ترجیح بیشتر به ${source.name}`} disabled={first || moving} onClick={() => onMove(-1)}><ArrowUp /></Button>
        <span className="tabular text-xs text-muted-foreground" aria-label={`اولویت ${f.number(rank)}`}>{f.number(rank)}</span>
        <Button variant="ghost" size="icon-sm" aria-label={`ترجیح کمتر به ${source.name}`} disabled={last || moving} onClick={() => onMove(1)}><ArrowDown /></Button>
      </div>
      <div className="min-w-0">
        <p className="flex flex-wrap items-center gap-2 font-medium">
          {source.name}
          <Badge variant="outline">{KIND_LABELS[source.kind]}</Badge>
          {source.usesKey ? <Badge variant="outline"><KeyRound />کلید</Badge> : null}
          {!source.enabled ? <Badge variant="secondary">خاموش</Badge> : null}
        </p>
        <p className="text-xs text-muted-foreground">
          {source.mappings.map((m) => commodities.get(m.commodity).nameFa).join('، ')} | هر {f.number(source.intervalMinutes)} دقیقه
        </p>
        {source.lastError ? (
          <p className="mt-1 flex items-start gap-1 text-xs text-destructive"><AlertTriangle className="mt-0.5 size-3.5 shrink-0" />{source.lastError}</p>
        ) : null}
        {source.lastSuccessAt ? (
          <p className="mt-1 flex items-center gap-1 text-xs text-muted-foreground">
            <CheckCircle2 className="size-3.5 text-income" aria-hidden />
            آخرین دریافت موفق: {formatTimeAgo(source.lastSuccessAt, f.prefs.digits)}
            {source.lastCount !== null ? ` | ${f.number(source.lastCount)} قیمت` : ''}
          </p>
        ) : null}
      </div>
      <div className="flex items-center gap-1">
        <Switch checked={source.enabled} aria-label={`دریافت خودکار از ${source.name}`} disabled={save.isPending}
          onCheckedChange={(v) => save.mutate({ id: source.id, ...toInput(source), enabled: v }, {
            onError: (e) => toast.error(e instanceof ApiError ? e.message : 'تغییر انجام نشد.'),
          })} />
        <Button variant="ghost" size="icon-sm" aria-label={`دریافت حالا از ${source.name}`} loading={run.isPending}
          onClick={() => run.mutate(source.id, {
            onSuccess: ([r]) => (r.error ? toast.error(r.error) : toast.success(`${f.number(r.results.filter((x) => !x.error).length)} قیمت دریافت شد.`)),
          })}>
          <RefreshCw />
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="icon-sm" aria-label={`گزینه‌های ${source.name}`}><MoreVertical /></Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            <DropdownMenuItem onSelect={onEdit}><Pencil />ویرایش</DropdownMenuItem>
            <DropdownMenuItem onSelect={onDelete} className="text-destructive focus:text-destructive"><Trash2 />حذف</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </li>
  )
}

const STATUS_LABELS: Record<QuoteStatus, string> = {
  CHOSEN: 'در استفاده', BACKUP: 'پشتیبان', OUTLIER: 'کنار گذاشته شد', STALE: 'قدیمی', OFF: 'منبع خاموش',
}

/** Where every commodity's price comes from, and how the sources compare. */
function Coverage({ rows }: { rows: PriceCoverage[] }) {
  const f = useFormat()
  const commodities = useCommodityMap()
  return (
    <div className="grid gap-2">
      <h3 className="text-sm font-medium">قیمت هر واحد از کجا می‌آید</h3>
      <ul className="divide-y rounded-xl border">
        {rows.map((row) => (
          <li key={row.commodity} className="grid gap-1.5 p-3">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <span className="font-medium">{commodities.get(row.commodity).nameFa}</span>
              {row.priceToman ? (
                <span className="text-sm"><Amount value={row.priceToman} /> <span className="text-xs text-muted-foreground">از {row.source}</span></span>
              ) : <span className="text-xs text-destructive">هیچ منبعی قیمت تازه ندارد</span>}
            </div>
            <ul className="flex flex-wrap gap-1.5">
              {row.quotes.map((q) => (
                <li key={q.sourceId} className={cn('rounded-lg border px-2 py-1 text-xs',
                  q.status === 'CHOSEN' && 'border-primary/40 bg-primary/5',
                  q.status === 'OUTLIER' && 'border-destructive/40 text-destructive',
                  (q.status === 'STALE' || q.status === 'OFF') && 'text-muted-foreground')}>
                  {q.sourceName}: <Amount value={q.priceToman} /> | {STATUS_LABELS[q.status]} | {formatTimeAgo(q.fetchedAt, f.prefs.digits)}
                </li>
              ))}
            </ul>
            {row.warning ? <p className="flex items-start gap-1 text-xs text-warning"><AlertTriangle className="mt-0.5 size-3.5 shrink-0" />{row.warning}</p> : null}
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Admin card: automatic price sources in order of preference, and where each price comes from. */
export function PriceSourcesCard() {
  const { data: sources, isPending } = usePriceSources(true)
  const { data: coverage } = usePriceCoverage(true)
  const remove = useDeletePriceSource()
  const reorder = useReorderPriceSources()
  const [editing, setEditing] = useState<{ source?: PriceSource } | null>(null)
  const [deleting, setDeleting] = useState<PriceSource | null>(null)

  const move = (index: number, step: -1 | 1) => {
    if (!sources) return
    const ids = sources.map((s) => s.id)
    ;[ids[index], ids[index + step]] = [ids[index + step], ids[index]]
    reorder.mutate(ids, { onError: (e) => toast.error(e instanceof ApiError ? e.message : 'ترتیب ذخیره نشد.') })
  }

  return (
    <Card>
      <CardHeader className="flex-row flex-wrap items-start justify-between gap-2">
        <div className="grid gap-1">
          <CardTitle>منابع قیمت خودکار</CardTitle>
          <CardDescription>
            قیمت هر واحد از بالاترین منبعی گرفته می‌شود که قیمت تازه دارد؛ منابع پایین‌تر پشتیبان‌اند و اگر منبعی خطا بدهد یا قیمتش خیلی با بقیه فرق کند، جای آن را می‌گیرند.
            قیمتی که کاربر دستی ثبت کند تا رسیدن قیمت تازه‌تر ملاک است.
          </CardDescription>
        </div>
        <Button variant="outline" size="sm" onClick={() => setEditing({})}><Plus />منبع جدید</Button>
      </CardHeader>
      <CardContent className="grid gap-4 pt-0">
        {isPending ? <Skeleton className="h-24" /> : !sources?.length ? (
          <p className="py-4 text-sm text-muted-foreground">منبعی تعریف نشده است.</p>
        ) : (
          <ul className="divide-y">
            {sources.map((s, i) => (
              <SourceRow key={s.id} source={s} rank={i + 1} first={i === 0} last={i === sources.length - 1}
                moving={reorder.isPending} onMove={(step) => move(i, step)}
                onEdit={() => setEditing({ source: s })} onDelete={() => setDeleting(s)} />
            ))}
          </ul>
        )}
        {coverage?.length ? <Coverage rows={coverage} /> : null}
      </CardContent>
      <Dialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)}>
        {editing ? <SourceDialog key={editing.source?.id ?? 'new'} source={editing.source} onClose={() => setEditing(null)} /> : null}
      </Dialog>
      <ConfirmDialog open={deleting !== null} onOpenChange={(o) => !o && setDeleting(null)} destructive
        title={`حذف منبع «${deleting?.name ?? ''}»؟`} description="قیمت‌هایی که تا حالا از این منبع ثبت شده می‌مانند." confirmLabel="حذف"
        loading={remove.isPending}
        onConfirm={() => deleting && remove.mutate(deleting.id, { onSuccess: () => { toast.success('منبع حذف شد.'); setDeleting(null) } })} />
    </Card>
  )
}
