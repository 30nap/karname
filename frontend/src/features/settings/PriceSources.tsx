import { AlertTriangle, CheckCircle2, FlaskConical, MoreVertical, Pencil, Plus, RefreshCw, Trash2, X } from 'lucide-react'
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
import type { PriceMapping, PriceRunResult, PriceSource, PriceSourceInput, PriceSourceKind, PriceUnit } from '@/lib/api/types'
import { formatTimeAgo } from '@/lib/format/duration'
import { useDeletePriceSource, usePriceSources, useRunPriceSources, useSavePriceSource, useTestPriceSource } from './api'

const KIND_LABELS: Record<PriceSourceKind, string> = { NOBITEX: 'نوبیتکس', JSON: 'API دلخواه' }

/** The stored form of a source, ready to send back with one field changed (header values stay on the server). */
function toInput(source: PriceSource): PriceSourceInput {
  return {
    name: source.name, kind: source.kind, url: source.url, unit: source.unit, mappings: source.mappings, intervalMinutes: source.intervalMinutes,
    enabled: source.enabled, headers: source.headers.map((h) => ({ name: h.name, value: null })),
  }
}

function TestResults({ result }: { result: PriceRunResult }) {
  const commodities = useCommodityMap()
  const f = useFormat()
  if (result.error) return <Alert variant="destructive"><AlertTriangle />{result.error}</Alert>
  return (
    <div className="overflow-x-auto rounded-xl border">
      <table className="w-full min-w-[22rem] text-sm">
        <thead className="bg-muted/60 text-xs text-muted-foreground">
          <tr>
            <th className="p-2 text-start font-medium">واحد</th>
            <th className="p-2 text-end font-medium">عدد خوانده‌شده</th>
            <th className="p-2 text-end font-medium">قیمت</th>
          </tr>
        </thead>
        <tbody>
          {result.results.map((r) => (
            <tr key={r.commodity} className="border-t">
              <td className="p-2">{commodities.get(r.commodity).nameFa}</td>
              {r.error ? (
                <td colSpan={2} className="p-2 text-end text-xs text-destructive">{r.error}</td>
              ) : (
                <>
                  <td className="p-2 text-end tabular text-muted-foreground">{f.number(r.raw ?? '0', { maxFraction: 8 })}</td>
                  <td className="p-2 text-end"><Amount value={r.priceToman} /></td>
                </>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function SourceForm({ source, onClose }: { source?: PriceSource; onClose: () => void }) {
  const { data: commodities = [] } = useCommodities()
  const save = useSavePriceSource()
  const test = useTestPriceSource()
  const [name, setName] = useState(source?.name ?? '')
  const [kind, setKind] = useState<PriceSourceKind>(source?.kind ?? 'JSON')
  const [url, setUrl] = useState(source?.url ?? '')
  const [unit, setUnit] = useState<PriceUnit>(source?.unit ?? 'TOMAN')
  const [headers, setHeaders] = useState<{ name: string; value: string; stored: boolean }[]>(
    source?.headers.map((h) => ({ name: h.name, value: '', stored: h.hasValue })) ?? [])
  const [mappings, setMappings] = useState<PriceMapping[]>(source?.mappings ?? [{ commodity: 'GOLD18', path: '', multiplier: null }])
  const [interval, setIntervalMinutes] = useState(String(source?.intervalMinutes ?? 30))
  const [enabled, setEnabled] = useState(source?.enabled ?? true)
  const [error, setError] = useState<string | null>(null)
  const results = useRef<HTMLDivElement>(null)
  const priced = commodities.filter((c) => !c.custom && c.code !== 'IRT')
  const nobitex = kind === 'NOBITEX'

  const input = (): PriceSourceInput => ({
    name: name.trim(), kind, url: url.trim() || null, unit: nobitex ? 'RIAL' : unit, intervalMinutes: Number(interval) || 30, enabled,
    headers: headers.filter((h) => h.name.trim()).map((h) => ({ name: h.name.trim(), value: h.value || null })),
    mappings: mappings.map((m) => ({ commodity: m.commodity, path: m.path.trim(), multiplier: m.multiplier?.trim() || null })),
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
    <DialogContent className="sm:max-w-2xl">
      <form onSubmit={submit} className="contents">
        <DialogHeader>
          <DialogTitle>{source ? `ویرایش «${source.name}»` : 'منبع قیمت جدید'}</DialogTitle>
          <DialogDescription>
            {nobitex
              ? 'قیمت رمزارزها از بازارهای ریالی نوبیتکس، بدون نیاز به کلید. تتر را می‌توانید به دلار هم نسبت دهید تا نرخ دلار آزاد خودکار شود.'
              : 'هر سرویسی که قیمت را به‌صورت JSON می‌دهد: برای هر واحد، مسیر عدد در پاسخ را با اشاره‌گر JSON بنویسید؛ مثلاً /data/gold18/price.'}
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
          <FormField label="نشانی" optional={nobitex} hint={nobitex ? 'پیش‌فرض: https://apiv2.nobitex.ir' : undefined}>
            <Input value={url} onChange={(e) => setUrl(e.target.value)} dir="ltr" className="text-start" inputMode="url"
              placeholder={nobitex ? 'https://apiv2.nobitex.ir' : 'https://example.com/prices.json'} />
          </FormField>
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
              <div key={i} className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)_5.5rem_auto] gap-2">
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
                <Button type="button" variant="ghost" size="icon" aria-label="حذف قیمت" disabled={mappings.length === 1}
                  onClick={() => setMappings(mappings.filter((_, j) => j !== i))}><X /></Button>
              </div>
            ))}
            <Button type="button" variant="outline" size="sm" className="w-fit"
              onClick={() => setMappings([...mappings, { commodity: priced.find((c) => !mappings.some((m) => m.commodity === c.code))?.code ?? 'USD', path: '', multiplier: null }])}>
              <Plus />قیمت
            </Button>
            <p className="text-xs text-muted-foreground">ضریب اختیاری است؛ مثلاً اگر سرویس قیمت هر مثقال را می‌دهد و شما قیمت هر گرم را می‌خواهید.</p>
          </fieldset>
          <div className="grid grid-cols-1 items-end gap-4 sm:grid-cols-2">
            <FormField label="به‌روزرسانی هر (دقیقه)" hint="بین ۵ دقیقه تا ۲۴ ساعت">
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
    </DialogContent>
  )
}

function SourceRow({ source, onEdit, onDelete }: { source: PriceSource; onEdit: () => void; onDelete: () => void }) {
  const f = useFormat()
  const commodities = useCommodityMap()
  const save = useSavePriceSource()
  const run = useRunPriceSources()
  return (
    <li className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-x-3 gap-y-2 py-3">
      <div className="min-w-0">
        <p className="flex flex-wrap items-center gap-2 font-medium">
          {source.name}
          <Badge variant="outline">{KIND_LABELS[source.kind]}</Badge>
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

/** Admin card: automatic price sources (Nobitex and any JSON API). */
export function PriceSourcesCard() {
  const { data: sources, isPending } = usePriceSources(true)
  const remove = useDeletePriceSource()
  const [editing, setEditing] = useState<{ source?: PriceSource } | null>(null)
  const [deleting, setDeleting] = useState<PriceSource | null>(null)
  return (
    <Card>
      <CardHeader className="flex-row flex-wrap items-start justify-between gap-2">
        <div className="grid gap-1">
          <CardTitle>منابع قیمت خودکار</CardTitle>
          <CardDescription>
            قیمت‌ها در فاصله‌ی تعیین‌شده گرفته و برای همه‌ی کاربران ثبت می‌شوند. قیمتی که کاربر دستی ثبت کند تا رسیدن قیمت تازه‌تر ملاک است.
          </CardDescription>
        </div>
        <Button variant="outline" size="sm" onClick={() => setEditing({})}><Plus />منبع جدید</Button>
      </CardHeader>
      <CardContent className="pt-0">
        {isPending ? <Skeleton className="h-24" /> : !sources?.length ? (
          <p className="py-4 text-sm text-muted-foreground">منبعی تعریف نشده است.</p>
        ) : (
          <ul className="divide-y">
            {sources.map((s) => <SourceRow key={s.id} source={s} onEdit={() => setEditing({ source: s })} onDelete={() => setDeleting(s)} />)}
          </ul>
        )}
      </CardContent>
      <Dialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)}>
        {editing ? <SourceForm key={editing.source?.id ?? 'new'} source={editing.source} onClose={() => setEditing(null)} /> : null}
      </Dialog>
      <ConfirmDialog open={deleting !== null} onOpenChange={(o) => !o && setDeleting(null)} destructive
        title={`حذف منبع «${deleting?.name ?? ''}»؟`} description="قیمت‌هایی که تا حالا از این منبع گرفته شده می‌مانند." confirmLabel="حذف"
        loading={remove.isPending}
        onConfirm={() => deleting && remove.mutate(deleting.id, { onSuccess: () => { toast.success('منبع حذف شد.'); setDeleting(null) } })} />
    </Card>
  )
}
