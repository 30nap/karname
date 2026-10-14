import { RefreshCw } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { useFormat } from '@/app/preferences'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import type { Commodity, SecurityInstrument } from '@/lib/api/types'
import { useRefreshSecurity, useSaveSecurity, useUnlinkSecurity } from './api'
import { SecurityPicker } from './SecurityPicker'

export function SecurityPricing({ commodity }: { commodity: Commodity }) {
  const f = useFormat()
  const save = useSaveSecurity()
  const refresh = useRefreshSecurity()
  const unlink = useUnlinkSecurity()
  const [selected, setSelected] = useState<SecurityInstrument | null>(null)
  const [linking, setLinking] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const stock = commodity.stock
  const pending = save.isPending || refresh.isPending || unlink.isPending

  return (
    <section className="grid gap-3 rounded-xl border p-3" aria-label="قیمت خودکار بورس">
      <p className="text-sm font-medium">قیمت خودکار بورس ایران{stock ? ` · ${stock.symbol}` : ''}</p>
      {stock ? (
        <>
          <p className="text-xs text-muted-foreground">قیمت پایانی هر ۳۰ دقیقه از TSETMC دریافت و از ریال به تومان تبدیل می‌شود. زمان قیمت مربوط به آخرین معامله است.</p>
          {stock.lastSuccessAt ? <p className="text-xs text-muted-foreground">آخرین دریافت موفق: {f.dateTime(stock.lastSuccessAt)}</p> : null}
          {stock.lastError ? <Alert variant="destructive">{stock.lastError} قیمت قبلی تا دریافت موفق بعدی حفظ می‌شود.</Alert> : null}
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="outline" size="sm" disabled={pending} loading={refresh.isPending} onClick={() => {
              setError(null)
              refresh.mutate(commodity.code, {
                onSuccess: (updated) => {
                  if (!updated.stock?.lastError) toast.success('قیمت نماد دریافت شد.')
                },
                onError: (e) => setError(e.message),
              })
            }}><RefreshCw />دریافت قیمت نماد</Button>
            <Button type="button" variant="ghost" size="sm" disabled={pending} loading={unlink.isPending} onClick={() => {
              setError(null)
              unlink.mutate(commodity.code, {
                onSuccess: () => { setLinking(false); setSelected(null); toast.success('دریافت خودکار متوقف شد؛ قیمت‌های قبلی حفظ شدند.') },
                onError: (e) => setError(e.message),
              })
            }}>توقف دریافت خودکار</Button>
          </div>
        </>
      ) : linking ? (
        <>
          <p className="text-xs text-muted-foreground">نماد همین دارایی را انتخاب کنید تا قیمت آن خودکار دریافت شود. موجودی و تراکنش‌های شما حفظ می‌شوند.</p>
          <SecurityPicker value={selected} onChange={setSelected} />
          <div className="flex gap-2">
            <Button type="button" size="sm" disabled={!selected || pending} loading={save.isPending} onClick={() => {
              if (!selected) return
              setError(null)
              save.mutate({ code: commodity.code, instrumentId: selected.instrumentId }, {
                onSuccess: () => { setLinking(false); setSelected(null); toast.success('نماد به دارایی متصل شد.') },
                onError: (e) => setError(e.message),
              })
            }}>اتصال نماد</Button>
            <Button type="button" size="sm" variant="ghost" disabled={pending} onClick={() => { setLinking(false); setError(null) }}>انصراف</Button>
          </div>
        </>
      ) : <Button type="button" size="sm" variant="outline" className="w-fit" onClick={() => setLinking(true)}>اتصال به نماد بورس</Button>}
      {error ? <Alert variant="destructive">{error}</Alert> : null}
    </section>
  )
}
