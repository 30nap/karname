import { AlertTriangle, Coins, History, Plus, RefreshCw, Trash2 } from 'lucide-react'
import Big from 'big.js'
import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { FormField } from '@/components/ui/form-field'
import { Input } from '@/components/ui/input'
import { EmptyState } from '@/components/ui/empty-state'
import { PageHeader } from '@/components/ui/page-header'
import { Segmented } from '@/components/ui/segmented'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { Amount } from '@/components/finance/Amount'
import { MoneyInput } from '@/components/finance/MoneyInput'
import { JalaliDatePicker } from '@/components/finance/JalaliDatePicker'
import { useAccounts } from '@/features/accounts/api'
import { useFormat, usePrefs } from '@/app/preferences'
import { useAuthStatus, useMe } from '@/features/auth/api'
import { useRunPriceSources } from '@/features/settings/api'
import type { Commodity, CommodityKind, SecurityInstrument } from '@/lib/api/types'
import { fromDisplayAmount, IRT, sumAmounts } from '@/lib/format/money'
import { todayIso } from '@/lib/jalali'
import { COMMODITY_KIND_LABELS } from '@/lib/labels'
import { useCommodities, useDeletePrice, usePriceHistory, useRecordPrice, useSaveCustomCommodity, useSaveSecurity } from './api'
import { SecurityPicker } from './SecurityPicker'
import { SecurityPricing } from './SecurityPricing'

const KIND_ORDER: CommodityKind[] = ['FIAT', 'GOLD', 'COIN', 'CRYPTO', 'SECURITY', 'PROPERTY', 'VEHICLE', 'OTHER']

function PriceDialog({ commodity, onClose }: { commodity: Commodity; onClose: () => void }) {
  const me = useMe()
  const prefs = usePrefs()
  const f = useFormat()
  const record = useRecordPrice()
  const history = usePriceHistory(commodity.code)
  const removePrice = useDeletePrice()
  const [value, setValue] = useState('')
  const [date, setDate] = useState(todayIso())
  const [global, setGlobal] = useState(false)
  const [error, setError] = useState<string | null>(null)

  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>قیمت {commodity.nameFa}</DialogTitle>
        <DialogDescription>قیمت هر {commodity.unitFa} به تومان. جدیدترین قیمت (دستی یا خودکار) ملاک ارزش‌گذاری است.</DialogDescription>
      </DialogHeader>
      <DialogBody className="grid gap-4">
        {error ? <Alert variant="destructive">{error}</Alert> : null}
        {commodity.custom && commodity.kind === 'SECURITY' && commodity.scale === 0 ? <SecurityPricing commodity={commodity} /> : null}
        <FormField label={`قیمت هر ${commodity.unitFa}`}>
          <MoneyInput value={value} onChange={setValue} commodity={IRT} autoFocus showQuickButtons />
        </FormField>
        <FormField label="تاریخ قیمت" hint="برای ثبت قیمت روزهای گذشته (مثلاً روز خرید) تاریخ را عوض کنید.">
          <JalaliDatePicker value={date} onChange={setDate} />
        </FormField>
        {me.role === 'ADMIN' && !commodity.custom ? (
          <label className="flex items-center justify-between gap-3 text-sm">
            <span>برای همه‌ی کاربران (قیمت عمومی)</span>
            <Switch checked={global} onCheckedChange={setGlobal} />
          </label>
        ) : null}
        <div>
          <p className="mb-2 flex items-center gap-1.5 text-sm font-medium"><History className="size-4" />تاریخچه</p>
          <div className="max-h-56 overflow-y-auto rounded-lg border">
            {history.isPending ? <Skeleton className="m-2 h-16" /> : history.data?.length ? (
              <table className="w-full text-sm">
                <thead className="sticky top-0 bg-muted text-xs text-muted-foreground">
                  <tr><th className="p-2 text-start font-medium">زمان</th><th className="p-2 text-start font-medium">قیمت</th><th className="p-2 text-start font-medium">منبع</th><th /></tr>
                </thead>
                <tbody>
                  {history.data.map((p) => (
                    <tr key={p.id} className="border-t">
                      <td className="p-2 tabular">{f.dateTime(p.pricedAt)}</td>
                      <td className="p-2 tabular"><Amount value={p.priceToman} /></td>
                      <td className="p-2 text-xs text-muted-foreground">{p.source === 'MANUAL' ? (p.personal ? 'دستی (شخصی)' : 'دستی (عمومی)') : p.source === 'TRANSACTION' ? 'از تراکنش' : p.source}</td>
                      <td className="p-1 text-end">
                        {(p.personal && p.source === 'MANUAL') || (!p.personal && me.role === 'ADMIN' && p.source === 'MANUAL') ? (
                          <Button variant="ghost" size="icon-sm" aria-label="حذف قیمت" onClick={() => removePrice.mutate(p.id)}><Trash2 /></Button>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : <p className="p-3 text-sm text-muted-foreground">هنوز قیمتی ثبت نشده است.</p>}
          </div>
        </div>
      </DialogBody>
      <DialogFooter>
        <Button variant="outline" onClick={onClose}>بستن</Button>
        <Button disabled={!value} loading={record.isPending} onClick={() => {
          setError(null)
          // A past day is recorded at noon Tehran time (UTC+3:30 all year); today means "now".
          const pricedAt = date === todayIso() ? undefined : `${date}T08:30:00Z`
          record.mutate({ commodity: commodity.code, priceToman: fromDisplayAmount(value, 'IRT', prefs), pricedAt, global }, {
            onSuccess: () => { toast.success('قیمت ثبت شد.'); setValue('') },
            onError: (e) => setError(e.message),
          })
        }}>ثبت قیمت</Button>
      </DialogFooter>
    </DialogContent>
  )
}

function CustomCommodityDialog({ onClose }: { onClose: () => void }) {
  const f = useFormat()
  const save = useSaveCustomCommodity()
  const saveSecurity = useSaveSecurity()
  const [mode, setMode] = useState<'manual' | 'security'>('manual')
  const [instrument, setInstrument] = useState<SecurityInstrument | null>(null)
  const [name, setName] = useState('')
  const [unit, setUnit] = useState('')
  const [kind, setKind] = useState<CommodityKind>('SECURITY')
  const [scale, setScale] = useState('0')
  const [error, setError] = useState<string | null>(null)
  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>واحد دارایی جدید</DialogTitle>
        <DialogDescription>{mode === 'security'
          ? 'نماد سهام یا صندوق بورسی را انتخاب کنید؛ قیمت پایانی خودکار از TSETMC دریافت و به تومان تبدیل می‌شود.'
          : 'برای سهام، واحد صندوق، ملک، خودرو یا هر دارایی دیگری که در فهرست نیست.'}</DialogDescription>
      </DialogHeader>
      <DialogBody className="grid gap-4">
        {error ? <Alert variant="destructive">{error}</Alert> : null}
        <Segmented ariaLabel="روش افزودن دارایی" value={mode} onChange={(value) => { setMode(value); setInstrument(null); setError(null) }}
          options={[{ value: 'manual', label: 'واحد دلخواه' }, { value: 'security', label: 'بورس ایران' }]} />
        {mode === 'security' ? (
          <>
            <SecurityPicker value={instrument} onChange={setInstrument} />
            <p className="text-xs text-muted-foreground">پس از افزودن نماد، در بخش حساب‌ها یک حساب «بورس و صندوق» با این واحد بسازید و تعداد دارایی یا خرید و فروش خود را ثبت کنید.</p>
          </>
        ) : <>
        <FormField label="نام"><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="مثلاً صندوق طلای عیار" /></FormField>
        <FormField label="واحد شمارش"><Input value={unit} onChange={(e) => setUnit(e.target.value)} placeholder="مثلاً واحد، سهم، باب" /></FormField>
        <FormField label="نوع">
          <Select value={kind} onValueChange={(v) => setKind(v as CommodityKind)}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              {(['SECURITY', 'PROPERTY', 'VEHICLE', 'OTHER', 'GOLD', 'COIN', 'CRYPTO', 'FIAT'] as CommodityKind[]).map((k) => (
                <SelectItem key={k} value={k}>{COMMODITY_KIND_LABELS[k]}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </FormField>
        <FormField label="تعداد رقم اعشار" hint="برای واحدهای شمارشی مثل سهم، صفر.">
          <Select value={scale} onValueChange={setScale}>
            <SelectTrigger className="w-32"><SelectValue /></SelectTrigger>
            <SelectContent>{['0', '1', '2', '3', '4', '6', '8'].map((s) => <SelectItem key={s} value={s}>{f.digits(s)}</SelectItem>)}</SelectContent>
          </Select>
        </FormField>
        </>}
      </DialogBody>
      <DialogFooter>
        <Button variant="outline" onClick={onClose}>انصراف</Button>
        <Button loading={save.isPending || saveSecurity.isPending} disabled={mode === 'security' && !instrument} onClick={() => {
          setError(null)
          if (mode === 'security') {
            if (!instrument) return
            saveSecurity.mutate({ instrumentId: instrument.instrumentId }, {
              onSuccess: (commodity) => {
                if (commodity.stock?.lastError) toast.warning(`نماد اضافه شد؛ دریافت قیمت انجام نشد: ${commodity.stock.lastError}`)
                else toast.success('نماد اضافه شد و دریافت خودکار قیمت فعال است.')
                onClose()
              },
              onError: (e) => setError(e.message),
            })
          } else save.mutate({ nameFa: name, unitFa: unit, kind, scale: Number(scale) }, {
            onSuccess: () => { toast.success('واحد ساخته شد.'); onClose() },
            onError: (e) => setError(e.message),
          })
        }}>{mode === 'security' ? 'افزودن نماد' : 'ساخت'}</Button>
      </DialogFooter>
    </DialogContent>
  )
}

/** Quantity held per commodity across the user's open accounts (liabilities count negative). */
function useHoldings() {
  const { data: accounts } = useAccounts()
  return useMemo(() => {
    const byCode = new Map<string, string[]>()
    accounts?.filter((a) => a.commodity !== 'IRT').forEach((a) => byCode.set(a.commodity, [...(byCode.get(a.commodity) ?? []), a.balance]))
    const holdings = new Map<string, string>()
    byCode.forEach((balances, code) => {
      const total = sumAmounts(balances)
      if (Number(total) !== 0) holdings.set(code, total)
    })
    return { holdings, loaded: accounts !== undefined }
  }, [accounts])
}

type Scope = 'mine' | 'all'

function CommodityRow({ commodity: c, holding, onSelect }: { commodity: Commodity; holding?: string; onSelect: () => void }) {
  const f = useFormat()
  return (
    <button type="button" onClick={onSelect}
      className="flex cursor-pointer items-center justify-between gap-3 rounded-lg px-3 py-2.5 text-start hover:bg-accent/60">
      <span className="min-w-0">
        <span className="flex items-center gap-2 font-medium">{c.nameFa}{c.custom ? <Badge variant="outline">شخصی</Badge> : null}</span>
        {holding ? (
          <span className="block text-xs text-muted-foreground">
            موجودی شما: <Amount value={holding} commodity={c.code} className="text-foreground" />
            {c.latestPrice ? <> ≈ <Amount value={new Big(holding).times(c.latestPrice.priceToman).toFixed(0)} compact /></> : null}
          </span>
        ) : null}
        <span className="block text-xs text-muted-foreground">
          {c.latestPrice ? `هر ${c.unitFa} | ${f.dateTime(c.latestPrice.pricedAt)}` : `هر ${c.unitFa}`}
          {c.stock ? ` | نماد ${c.stock.symbol} · قیمت خودکار` : ''}
        </span>
        {c.stock?.lastError ? <span className="mt-1 block text-xs text-destructive">دریافت قیمت نماد ناموفق بود؛ برای جزئیات و تلاش دوباره باز کنید.</span> : null}
      </span>
      <span className="flex shrink-0 items-center gap-2">
        {c.latestPrice?.stale ? <AlertTriangle className="size-4 text-warning" aria-label="قیمت قدیمی" /> : null}
        {c.latestPrice ? <Amount value={c.latestPrice.priceToman} className="font-semibold" /> : <span className="text-sm text-primary">ثبت قیمت</span>}
      </span>
    </button>
  )
}

/** Admins fetch all automatic sources now; without an enabled source, points to the settings. */
function RefreshPricesButton() {
  const f = useFormat()
  const navigate = useNavigate()
  const run = useRunPriceSources()
  return (
    <Button variant="outline" loading={run.isPending} onClick={() => run.mutate('all', {
      onSuccess: (results) => {
        if (results.length === 0) {
          toast.info('هیچ منبع قیمت خودکاری روشن نیست.', { action: { label: 'تنظیمات', onClick: () => navigate('/settings?tab=admin') } })
          return
        }
        const fetched = results.reduce((n, r) => n + r.results.filter((x) => !x.error).length, 0)
        const failed = results.filter((r) => r.error)
        if (failed.length > 0) toast.error(`${f.number(failed.length)} منبع پاسخ نداد: ${failed[0].error}`)
        else toast.success(`${f.number(fetched)} قیمت به‌روز شد.`)
      },
    })}>
      <RefreshCw />به‌روزرسانی قیمت‌ها
    </Button>
  )
}

export function AssetsPage() {
  const admin = useAuthStatus().data?.user?.role === 'ADMIN'
  const { data: commodities, isPending } = useCommodities()
  const { holdings, loaded } = useHoldings()
  const [selectedCode, setSelectedCode] = useState<string | null>(null)
  const selected = commodities?.find((c) => c.code === selectedCode) ?? null
  const [customOpen, setCustomOpen] = useState(false)
  const [scopeChoice, setScope] = useState<Scope | null>(null)
  // Until the user picks, show their own assets when they have any.
  const scope: Scope = scopeChoice ?? (holdings.size > 0 ? 'mine' : 'all')
  const visible = (commodities ?? []).filter((c) => c.code !== 'IRT' && (scope === 'all' || holdings.has(c.code) || c.custom))

  return (
    <>
      <PageHeader title="دارایی‌ها و قیمت‌ها" description="قیمت روز ارز، طلا، سکه، رمزارز و بورس برای محاسبه‌ی ارزش دارایی‌ها"
        actions={<>
          {admin ? <RefreshPricesButton /> : null}
          <Button variant="outline" onClick={() => setCustomOpen(true)}><Plus />واحد جدید</Button>
        </>} />
      {isPending || !loaded ? <Skeleton className="h-64" /> : (
        <div className="grid gap-4">
          <Segmented<Scope>
            ariaLabel="نمایش"
            value={scope}
            onChange={setScope}
            className="w-full sm:w-80"
            options={[{ value: 'mine', label: 'دارایی‌های من' }, { value: 'all', label: 'همه‌ی واحدها' }]}
          />
          {visible.length === 0 ? (
            <EmptyState icon={Coins} title="هنوز دارایی ارزی، طلا یا رمزارز ندارید"
              description="با ساخت حساب ارزی، طلا یا رمزارز، واحدش این‌جا نمایش داده می‌شود. برای دیدن قیمت همه‌ی واحدها «همه‌ی واحدها» را بزنید." />
          ) : null}
          {KIND_ORDER.map((kind) => {
            const items = visible.filter((c) => c.kind === kind)
            if (!items.length) return null
            return (
              <Card key={kind}>
                <CardHeader><CardTitle>{COMMODITY_KIND_LABELS[kind]}</CardTitle></CardHeader>
                <CardContent className="grid gap-1 p-2 sm:p-3">
                  {items.map((c) => <CommodityRow key={c.code} commodity={c} holding={holdings.get(c.code)} onSelect={() => setSelectedCode(c.code)} />)}
                </CardContent>
              </Card>
            )
          })}
          <Card>
            <CardHeader>
              <CardTitle>به‌روزرسانی خودکار</CardTitle>
              <CardDescription>برای سهام و صندوق‌های بورس ایران، از «واحد جدید» نماد را انتخاب کنید تا قیمت آن خودکار دریافت شود. دریافت خودکار قیمت ارز، طلا و رمزارز را مدیر سیستم در تنظیمات فعال می‌کند. قیمت دستی و قیمت خرید و فروش با تومان هم برای ارزش‌گذاری ثبت می‌شوند.</CardDescription>
            </CardHeader>
          </Card>
        </div>
      )}
      <Dialog open={!!selected} onOpenChange={(o) => !o && setSelectedCode(null)}>
        {selected ? <PriceDialog commodity={selected} onClose={() => setSelectedCode(null)} /> : null}
      </Dialog>
      <Dialog open={customOpen} onOpenChange={setCustomOpen}>
        {customOpen ? <CustomCommodityDialog onClose={() => setCustomOpen(false)} /> : null}
      </Dialog>
    </>
  )
}
