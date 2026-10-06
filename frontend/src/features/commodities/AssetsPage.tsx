import { AlertTriangle, Coins, History, Plus, Trash2 } from 'lucide-react'
import Big from 'big.js'
import { useMemo, useState } from 'react'
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
import { useMe } from '@/features/auth/api'
import type { Commodity, CommodityKind } from '@/lib/api/types'
import { fromDisplayAmount, IRT, sumAmounts } from '@/lib/format/money'
import { todayIso } from '@/lib/jalali'
import { COMMODITY_KIND_LABELS } from '@/lib/labels'
import { useCommodities, useDeletePrice, usePriceHistory, useRecordPrice, useSaveCustomCommodity } from './api'

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
  const [name, setName] = useState('')
  const [unit, setUnit] = useState('')
  const [kind, setKind] = useState<CommodityKind>('SECURITY')
  const [scale, setScale] = useState('0')
  const [error, setError] = useState<string | null>(null)
  return (
    <DialogContent>
      <DialogHeader>
        <DialogTitle>واحد دارایی جدید</DialogTitle>
        <DialogDescription>برای سهام، واحد صندوق، ملک، خودرو یا هر دارایی دیگری که در فهرست نیست.</DialogDescription>
      </DialogHeader>
      <DialogBody className="grid gap-4">
        {error ? <Alert variant="destructive">{error}</Alert> : null}
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
      </DialogBody>
      <DialogFooter>
        <Button variant="outline" onClick={onClose}>انصراف</Button>
        <Button loading={save.isPending} onClick={() => save.mutate({ nameFa: name, unitFa: unit, kind, scale: Number(scale) }, {
          onSuccess: () => { toast.success('واحد ساخته شد.'); onClose() },
          onError: (e) => setError(e.message),
        })}>ساخت</Button>
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
        </span>
      </span>
      <span className="flex shrink-0 items-center gap-2">
        {c.latestPrice?.stale ? <AlertTriangle className="size-4 text-warning" aria-label="قیمت قدیمی" /> : null}
        {c.latestPrice ? <Amount value={c.latestPrice.priceToman} className="font-semibold" /> : <span className="text-sm text-primary">ثبت قیمت</span>}
      </span>
    </button>
  )
}

export function AssetsPage() {
  const { data: commodities, isPending } = useCommodities()
  const { holdings, loaded } = useHoldings()
  const [selected, setSelected] = useState<Commodity | null>(null)
  const [customOpen, setCustomOpen] = useState(false)
  const [scopeChoice, setScope] = useState<Scope | null>(null)
  // Until the user picks, show their own assets when they have any.
  const scope: Scope = scopeChoice ?? (holdings.size > 0 ? 'mine' : 'all')
  const visible = (commodities ?? []).filter((c) => c.code !== 'IRT' && (scope === 'all' || holdings.has(c.code) || c.custom))

  return (
    <>
      <PageHeader title="دارایی‌ها و قیمت‌ها" description="قیمت روز ارز، طلا، سکه و رمزارز برای محاسبه‌ی ارزش دارایی‌ها"
        actions={<Button variant="outline" onClick={() => setCustomOpen(true)}><Plus />واحد جدید</Button>} />
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
                  {items.map((c) => <CommodityRow key={c.code} commodity={c} holding={holdings.get(c.code)} onSelect={() => setSelected(c)} />)}
                </CardContent>
              </Card>
            )
          })}
          <Card>
            <CardHeader>
              <CardTitle>به‌روزرسانی خودکار</CardTitle>
              <CardDescription>دریافت خودکار قیمت از منابع آنلاین را مدیر سیستم در تنظیمات فعال می‌کند. تا آن موقع قیمت‌ها را دستی ثبت کنید؛ خرید و فروش طلا و ارز با تومان هم قیمت را خودکار ثبت می‌کند.</CardDescription>
            </CardHeader>
          </Card>
        </div>
      )}
      <Dialog open={!!selected} onOpenChange={(o) => !o && setSelected(null)}>
        {selected ? <PriceDialog commodity={selected} onClose={() => setSelected(null)} /> : null}
      </Dialog>
      <Dialog open={customOpen} onOpenChange={setCustomOpen}>
        {customOpen ? <CustomCommodityDialog onClose={() => setCustomOpen(false)} /> : null}
      </Dialog>
    </>
  )
}
