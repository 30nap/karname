import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { FormField } from '@/components/ui/form-field'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Segmented } from '@/components/ui/segmented'
import { Switch } from '@/components/ui/switch'
import { useMe, useUpdateSettings } from '@/features/auth/api'
import type { Settings } from '@/lib/api/types'
import { parseDecimalInput, toPersianDigits } from '@/lib/persian/digits'
import { useWealthUnitOptions } from './wealthUnits'

function useSettingsDraft() {
  const me = useMe()
  return useState<Settings>(me.settings)
}

export function DisplaySettings() {
  const [draft, setDraft] = useSettingsDraft()
  const update = useUpdateSettings()
  const options = useWealthUnitOptions()
  const [inflation, setInflation] = useState(draft.inflationRate ? toPersianDigits(draft.inflationRate) : '')

  const toggleUnit = (code: string, on: boolean) =>
    setDraft((d) => ({ ...d, wealthUnits: on ? [...d.wealthUnits, code].slice(0, 4) : d.wealthUnits.filter((c) => c !== code) }))

  const save = () => {
    const rate = inflation.trim() === '' ? null : parseDecimalInput(inflation)
    if (inflation.trim() !== '' && rate === null) {
      toast.error('نرخ تورم را به‌صورت عدد وارد کنید.')
      return
    }
    update.mutate({ ...draft, inflationRate: rate }, { onSuccess: () => toast.success('تنظیمات ذخیره شد.') })
  }

  return (
    <div className="grid grid-cols-1 gap-4">
      <Card>
        <CardHeader>
          <CardTitle>نمایش</CardTitle>
          <CardDescription>واحد پول، ارقام و پوسته‌ی برنامه</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-5">
          <div className="grid gap-2">
            <Label>واحد نمایش مبالغ</Label>
            <Segmented ariaLabel="واحد نمایش" value={draft.displayUnit} onChange={(v) => setDraft({ ...draft, displayUnit: v })}
              options={[{ value: 'TOMAN', label: 'تومان' }, { value: 'RIAL', label: 'ریال' }]} className="max-w-xs" />
            <p className="text-xs text-muted-foreground">مبالغ همیشه به تومان ذخیره می‌شوند؛ این گزینه فقط نمایش را تغییر می‌دهد.</p>
          </div>
          <div className="grid gap-2">
            <Label>ارقام</Label>
            <Segmented ariaLabel="ارقام" value={draft.digitStyle} onChange={(v) => setDraft({ ...draft, digitStyle: v })}
              options={[{ value: 'PERSIAN', label: '۱۲۳ فارسی' }, { value: 'LATIN', label: '123 لاتین' }]} className="max-w-xs" />
          </div>
          <div className="grid gap-2">
            <Label>پوسته</Label>
            <Segmented ariaLabel="پوسته" value={draft.theme} onChange={(v) => setDraft({ ...draft, theme: v })}
              options={[{ value: 'SYSTEM', label: 'خودکار' }, { value: 'LIGHT', label: 'روشن' }, { value: 'DARK', label: 'تیره' }]} className="max-w-sm" />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>سنجش ثروت</CardTitle>
          <CardDescription>با تورم بالا، ارزش دارایی به تومان گمراه‌کننده است. ثروتت را با این واحدها هم ببین (حداکثر ۴ مورد).</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4">
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
            {options.map((o) => (
              <label key={o.code} className="flex cursor-pointer items-center gap-2 rounded-lg border p-2.5 text-sm hover:bg-accent">
                <Checkbox checked={draft.wealthUnits.includes(o.code)} onCheckedChange={(v) => toggleUnit(o.code, v === true)} />
                {o.label}
              </label>
            ))}
          </div>
          <FormField label="نرخ تورم سالانه‌ی فرضی (درصد)" optional hint="برای محاسبه‌ی ارزش واقعی در پیش‌بینی‌ها و تحلیل‌ها.">
            <Input inputMode="decimal" value={inflation} onChange={(e) => setInflation(e.target.value)} className="max-w-40" placeholder="مثلاً ۳۵" />
          </FormField>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>هوش مصنوعی</CardTitle>
          <CardDescription>دستیار هوشمند فقط اعداد محاسبه‌شده را می‌خواند و بدون تأیید تو چیزی ثبت نمی‌کند.</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4">
          <label className="flex items-center justify-between gap-4">
            <span>
              <span className="block text-sm font-medium">فعال بودن دستیار</span>
              <span className="block text-xs text-muted-foreground">چت، ثبت سریع با متن، ورود پیامک و گزارش هوشمند</span>
            </span>
            <Switch checked={draft.aiEnabled} onCheckedChange={(v) => setDraft({ ...draft, aiEnabled: v })} />
          </label>
          <label className="flex items-center justify-between gap-4">
            <span>
              <span className="block text-sm font-medium">ارسال شرح تراکنش‌ها</span>
              <span className="block text-xs text-muted-foreground">اگر خاموش باشد، فقط مبالغ و دسته‌ها به سرویس AI فرستاده می‌شوند.</span>
            </span>
            <Switch checked={draft.aiShareDescriptions} onCheckedChange={(v) => setDraft({ ...draft, aiShareDescriptions: v })} />
          </label>
        </CardContent>
      </Card>

      <div className="flex justify-end">
        <Button onClick={save} loading={update.isPending}>ذخیره‌ی تنظیمات</Button>
      </div>
    </div>
  )
}
